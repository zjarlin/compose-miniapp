package compose.miniapp.compiler

// ---------- 解析异常 ----------

class ParseException(msg: String, val line: Int, val col: Int) :
    Exception("[$line:$col] $msg")

// ---------- 递归下降解析器 ----------
// 只接受受限 DSL 语法（文档 §2/§4.3），其余一律报解析错误。

class Parser(private val src: String, private val fileName: String) {
    private val tokens = Tokenizer(src).tokenize()
    private var pos = 0

    private fun peek(k: Int = 0): Token = tokens.getOrElse(pos + k) { Token(TokKind.EOF, "", 0, 0) }
    private fun next(): Token = tokens[pos++]
    private fun at(k: TokKind): Boolean = peek().kind == k
    private fun expect(k: TokKind, what: String): Token {
        if (!at(k)) fail("期望 $what，实际 ${describe(peek())}")
        return next()
    }

    private fun fail(msg: String): Nothing {
        val t = peek()
        throw ParseException(msg, t.line, t.col)
    }

    private fun describe(t: Token): String =
        when (t.kind) {
            TokKind.EOF -> "文件结束"
            TokKind.STRING -> "字符串 \"${t.text}\""
            else -> "'${t.text}'"
        }

    fun parseFile(): SourceFile {
        var entryPath: String? = null
        var pageFun: PageModel? = null
        var stateClassName: String? = null
        var stateFields = emptyList<StateField>()
        val pendingAnnotations = mutableListOf<String>()

        while (!at(TokKind.EOF)) {
            var consumed = false
            when {
                at(TokKind.AT) -> {
                    next()
                    val name = expect(TokKind.IDENT, "注解名").text
                    if (at(TokKind.LPAREN)) {
                        // @EntryPoint("/pages/x/x")
                        next()
                        val arg = expect(TokKind.STRING, "注解字符串参数").text
                        expect(TokKind.RPAREN, ")")
                        if (name == "EntryPoint") entryPath = arg
                    }
                    pendingAnnotations.add(name)
                }
                at(TokKind.IDENT) && peek().text == "package" -> {
                    // package 语句：仅消费，不参与转译
                    next() // package
                    if (at(TokKind.IDENT)) {
                        next()
                        while (at(TokKind.DOT) && peek(1).kind == TokKind.IDENT) { next(); next() }
                    }
                }
                at(TokKind.IDENT) && peek().text == "import" -> {
                    // import 语句：仅消费，不参与转译（import a.b.c.* / import a.b as X）
                    next() // import
                    if (at(TokKind.IDENT)) {
                        next()
                        while (at(TokKind.DOT) && (peek(1).kind == TokKind.IDENT || peek(1).kind == TokKind.STAR)) {
                            next(); next()
                        }
                    }
                    if (at(TokKind.IDENT) && peek().text == "as") { next(); if (at(TokKind.IDENT)) next() }
                }
                at(TokKind.IDENT) && peek().text == "data" && peek(1).kind == TokKind.IDENT && peek(1).text == "class" -> {
                    // data class（普通数据类，非状态类）：完整跳过
                    next(); next() // data class
                    if (at(TokKind.IDENT)) next() // 类名
                    if (at(TokKind.LANGLE)) { // 泛型
                        var depth = 0
                        while (!at(TokKind.EOF)) {
                            val tk = next()
                            if (tk.kind == TokKind.LANGLE) depth++
                            else if (tk.kind == TokKind.RANGLE) { depth--; if (depth == 0) break }
                        }
                    }
                    if (at(TokKind.LPAREN)) skipBalancedParen()
                    if (at(TokKind.COLON)) { next(); if (at(TokKind.IDENT)) next() }
                    if (at(TokKind.LBRACE)) skipBalancedBrace()
                }
                at(TokKind.IDENT) && peek().text == "class" -> {
                    consumed = true
                    next()
                    val clsName = expect(TokKind.IDENT, "类名").text
                    stateClassName = clsName
                    expect(TokKind.LBRACE, "{")
                    stateFields = parseStateClassBody()
                }
                at(TokKind.IDENT) && peek().text == "fun" -> {
                    consumed = true
                    next()
                    val funName = expect(TokKind.IDENT, "函数名").text
                    expect(TokKind.LPAREN, "(")
                    val params = parseParams()
                    expect(TokKind.RPAREN, ")")
                    expect(TokKind.LBRACE, "{")
                    val body = parseBlockBody()
                    if (pendingAnnotations.contains("EntryPoint") && entryPath != null) {
                        val scopeParam = params.firstOrNull { it.second == "PageScope" }?.first ?: "scope"
                        val stateParam = params.firstOrNull { it.second != "PageScope" }?.first
                        val bodyDecls = body.filterIsInstance<Stmt.StateDecl>().map { it.field }
                        val uiBody = body.filter { it !is Stmt.StateDecl }
                        pageFun = PageModel(entryPath!!, funName, scopeParam, stateParam, stateFields, bodyDecls, uiBody)
                    }
                }
                else -> fail("文件顶层只支持 class/注解/fun，实际 ${describe(peek())}")
            }
            // 注解只粘附给紧随的 class/fun：消费后才清空
            if (consumed) pendingAnnotations.clear()
        }
        return SourceFile(fileName, pageFun, stateClassName, stateFields)
    }

    private fun skipBalancedParen() {
        var depth = 0
        while (!at(TokKind.EOF)) {
            val tk = next()
            if (tk.kind == TokKind.LPAREN) depth++
            else if (tk.kind == TokKind.RPAREN) { depth--; if (depth == 0) break }
        }
    }

    private fun skipBalancedBrace() {
        var depth = 0
        while (!at(TokKind.EOF)) {
            val tk = next()
            if (tk.kind == TokKind.LBRACE) depth++
            else if (tk.kind == TokKind.RBRACE) { depth--; if (depth == 0) break }
        }
    }

    // class XxxState { var a by mutableStateOf(false) ... }
    private fun parseStateClassBody(): List<StateField> {
        val fields = mutableListOf<StateField>()
        while (!at(TokKind.RBRACE)) {
            if (at(TokKind.EOF)) fail("状态类缺少右括号 }")
            if (at(TokKind.IDENT) && peek().text == "var") {
                next()
                val name = expect(TokKind.IDENT, "字段名").text
                expect(TokKind.IDENT, "by").also { if (it.text != "by") fail("状态字段必须用 by mutableStateOf(...) 声明") }
                expect(TokKind.IDENT, "mutableStateOf").also { if (it.text != "mutableStateOf") fail("状态字段必须用 mutableStateOf 包裹") }
                expect(TokKind.LPAREN, "(")
                val raw = readBalancedUntilClose()
                val (rawInit, kind) = normalizeDefault(raw)
                fields.add(StateField(name, rawInit, kind))
                // 跳过可能存在的类型注解等，直到换行/;/
                skipToNextField()
            } else {
                fail("状态类内只支持 var 字段，实际 ${describe(peek())}")
            }
        }
        expect(TokKind.RBRACE, "}")
        return fields
    }

    // 读取 mutableStateOf( ... ) 括号内的原始文本（支持嵌套括号）
    private fun readBalancedUntilClose(): String {
        var depth = 1
        val sb = StringBuilder()
        while (true) {
            if (at(TokKind.EOF)) fail("mutableStateOf 括号未闭合")
            val t = next()
            when (t.kind) {
                TokKind.LPAREN -> { depth++; sb.append("(") }
                TokKind.RPAREN -> {
                    depth--
                    if (depth == 0) break
                    sb.append(")")
                }
                else -> sb.append(tokenText(t))
            }
        }
        return sb.toString().trim()
    }

    private fun tokenText(t: Token): String = when (t.kind) {
        TokKind.STRING -> "\"" + t.text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        TokKind.NUMBER -> t.text
        TokKind.IDENT -> t.text
        TokKind.COMMA -> ","
        TokKind.DOT -> "."
        TokKind.LBRACKET -> "["
        TokKind.RBRACKET -> "]"
        TokKind.ARROW -> "->"
        TokKind.LANGLE -> "<"
        TokKind.RANGLE -> ">"
        else -> " "
    }

    private fun normalizeDefault(raw: String): Pair<String, String> {
        val r = raw.trim()
        return when {
            r.startsWith("listOf") -> "[]" to "LIST"
            r == "true" -> "true" to "VALUE"
            r == "false" -> "false" to "VALUE"
            r.matches(Regex("-?\\d+(\\.\\d+)?")) -> r to "VALUE"
            r.startsWith("\"") && r.endsWith("\"") -> r to "VALUE"
            r.startsWith("'") && r.endsWith("'") -> "\"${r.substring(1, r.length - 1)}\"" to "VALUE"
            else -> throw ParseException("状态字段默认值不支持（仅支持 false/true/数字/字符串/listOf）：$r", peek().line, peek().col)
        }
    }

    private fun skipToNextField() {
        while (!at(TokKind.EOF) && !at(TokKind.RBRACE) && !(at(TokKind.IDENT) && peek().text == "var")) {
            // 跳过字段声明的其余部分：类型注解、=、函数调用等，直到下一个 var 或 }
            next()
        }
    }

    private fun parseParams(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        if (at(TokKind.RPAREN)) return out
        while (true) {
            val name = expect(TokKind.IDENT, "参数名").text
            expect(TokKind.COLON, ":")
            val type = expect(TokKind.IDENT, "参数类型").text
            out.add(name to type)
            if (at(TokKind.COMMA)) { next(); continue }
            break
        }
        return out
    }

    private fun parseBlockBody(): List<Stmt> {
        val stmts = mutableListOf<Stmt>()
        while (!at(TokKind.RBRACE)) {
            if (at(TokKind.EOF)) fail("块缺少右括号 }")
            stmts.add(parseStmt())
        }
        expect(TokKind.RBRACE, "}")
        return stmts
    }

    // var x by remember { mutableStateOf(init) } / val x = remember { mutableStateOf(init) }
    private fun parseStateDecl(line: Int, col: Int): Stmt {
        next() // var / val
        val name = expect(TokKind.IDENT, "状态名").text
        if (at(TokKind.IDENT) && peek().text == "by") {
            next()
        } else if (at(TokKind.EQ)) {
            next()
        } else fail("状态声明必须是 by remember 或 = remember 形式")
        expect(TokKind.IDENT, "remember").also { if (it.text != "remember") fail("状态必须用 remember { mutableStateOf(...) } 声明") }
        expect(TokKind.LBRACE, "{")
        expect(TokKind.IDENT, "mutableStateOf").also { if (it.text != "mutableStateOf") fail("remember 块内必须是 mutableStateOf(...)") }
        expect(TokKind.LPAREN, "(")
        val raw = readBalancedUntilClose()
        expect(TokKind.RBRACE, "}")
        val (rawInit, kind) = normalizeDefault(raw)
        return Stmt.StateDecl(StateField(name, rawInit, kind), line, col)
    }

    // if (cond) { ... }
    private fun parseIfStmt(line: Int, col: Int): Stmt {
        next() // if
        expect(TokKind.LPAREN, "(")
        val cond = parseValue()
        expect(TokKind.RPAREN, ")")
        expect(TokKind.LBRACE, "{")
        val body = parseBlockBody()
        return Stmt.If(cond, body, line, col)
    }

    private fun parseCallArgs(): List<NamedArg> {
        val args = mutableListOf<NamedArg>()
        next() // (
        if (!at(TokKind.RPAREN)) {
            while (true) {
                args.add(parseArg())
                if (at(TokKind.COMMA)) { next(); continue }
                break
            }
        }
        expect(TokKind.RPAREN, ")")
        return args
    }

    private fun parseStmt(): Stmt {
        val t = peek()
        val line = t.line; val col = t.col
        // 函数体内状态声明：var x by remember { mutableStateOf(...) } / val x = remember {...}
        if (at(TokKind.IDENT) && (peek().text == "var" || peek().text == "val")) {
            return parseStateDecl(line, col)
        }
        // 条件渲染语句：if (cond) { ... }
        if (at(TokKind.IDENT) && peek().text == "if") {
            return parseIfStmt(line, col)
        }
        // 赋值：IDENT(.IDENT)* = value
        if (at(TokKind.IDENT)) {
            // 前瞻：IDENT (. IDENT)* 后跟 =
            var k = 0
            if (peek(k).kind == TokKind.IDENT) {
                k++
                while (peek(k).kind == TokKind.DOT && peek(k + 1).kind == TokKind.IDENT) k += 2
            }
            if (peek(k).kind == TokKind.EQ) {
                val target = parseRef()
                expect(TokKind.EQ, "=")
                val v = parseValue()
                return Stmt.Assign(target, v, line, col)
            }
        }
        // 组件/函数调用
        val call = parseCall()
        return Stmt.Expr(call)
    }

    private fun parseRef(): Value.VRef {
        val parts = mutableListOf(expect(TokKind.IDENT, "标识符").text)
        while (at(TokKind.DOT) && peek(1).kind == TokKind.IDENT) {
            next()
            parts.add(next().text)
        }
        return Value.VRef(parts)
    }

    // IDENT(.IDENT)* ( args ) [ { ... } ]
    private fun parseCall(): Stmt.Call {
        val first = expect(TokKind.IDENT, "组件名")
        val line = first.line; val col = first.col
        var receiver: String? = null
        var name = first.text
        while (at(TokKind.DOT) && peek(1).kind == TokKind.IDENT) {
            next()
            val nxt = next().text
            if (receiver == null) receiver = name else receiver = "$receiver.$name"
            name = nxt
        }
        val args = mutableListOf<NamedArg>()
        if (at(TokKind.LPAREN)) {
            next()
            if (!at(TokKind.RPAREN)) {
                while (true) {
                    args.add(parseArg())
                    if (at(TokKind.COMMA)) { next(); continue }
                    break
                }
            }
            expect(TokKind.RPAREN, ")")
        }
        var lambdaParams = emptyList<String>()
        var body = emptyList<Stmt>()
        if (at(TokKind.LBRACE)) {
            next()
            // 块 lambda 参数：item -> 或 it（隐式）
            if (at(TokKind.IDENT) && peek(1).kind == TokKind.ARROW) {
                lambdaParams = listOf(next().text)
                next() // ->
            }
            body = parseBlockBody()
        }
        return Stmt.Call(receiver, name, args, lambdaParams, body, line, col)
    }

    private fun parseArg(): NamedArg {
        // 命名参数：IDENT = value
        if (at(TokKind.IDENT) && peek(1).kind == TokKind.EQ) {
            val name = next().text
            next() // =
            return NamedArg(name, parseValue())
        }
        return NamedArg(null, parseValue())
    }

    private fun parseValue(): Value {
        val t = peek()
        return when (t.kind) {
            TokKind.STRING -> Value.VStr(next().text)
            TokKind.NUMBER -> {
                val num = next()
                // 数字后面可能跟 .dp/.sp/.px（注意与属性引用区分）
                if (at(TokKind.DOT) && peek(1).kind == TokKind.IDENT &&
                    peek(1).text in setOf("dp", "sp", "px", "rpx")
                ) {
                    next()
                    val unit = next().text
                    Value.VUnit(num.text.toDouble(), unit)
                } else if (at(TokKind.IDENT) && peek().text == "f") {
                    // weight(1f) 等 Float 字面量后缀
                    next()
                    Value.VNum(num.text.toDouble())
                } else Value.VNum(num.text.toDouble())
            }
            TokKind.IDENT -> {
                when (t.text) {
                    "true", "false" -> { next(); Value.VBool(t.text == "true") }
                    else -> {
                        if (t.text == "listOf") {
                            fail("listOf(...) 只能作为状态字段默认值，不能出现在组件参数中")
                        }
                        val ref = parseRef()
                        if (!at(TokKind.LPAREN)) return ref
                        // 引用后跟 (：构造式/方法调用（TextStyle(...) / state.getTitle(...) /
                        // Modifier.fillMaxWidth().padding(16.dp)）。单层转 VCall，多层转 VChain，
                        // 合法性由 Analyzer 决定（方法调用报 E1001）
                        val parts = ref.parts
                        var receiver: String? = if (parts.size > 1) parts.dropLast(1).joinToString(".") else null
                        var name = parts.last()
                        val calls = mutableListOf<ChainCall>()
                        calls.add(ChainCall(receiver, name, parseCallArgs()))
                        while (at(TokKind.DOT) && peek(1).kind == TokKind.IDENT) {
                            next()
                            val nxt = next().text
                            if (!at(TokKind.LPAREN)) fail("链式调用 '$nxt' 缺少 (")
                            calls.add(ChainCall(null, nxt, parseCallArgs()))
                        }
                        if (calls.size == 1) {
                            val c0 = calls[0]
                            Value.VCall(c0.receiver, c0.name, c0.args)
                        } else Value.VChain(calls)
                    }
                }
            }
            TokKind.LBRACE -> {
                next()
                var params = emptyList<String>()
                if (at(TokKind.IDENT) && peek(1).kind == TokKind.ARROW) {
                    params = listOf(next().text); next()
                }
                val body = parseBlockBody()
                Value.VBlock(params, body)
            }
            else -> fail("无法识别的值 ${describe(t)}")
        }
    }
}
