package compose.miniapp.compiler

// ---------- Token ----------

enum class TokKind {
    IDENT,      // 标识符 / 关键字
    STRING,     // "..."（已去引号，保留转义内容）
    NUMBER,     // 数字（含小数）
    LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET,
    COMMA, DOT, EQ, COLON, ARROW, // -> 
    AT, LANGLE, RANGLE, STAR, // @ < > *
    EOF,
}

data class Token(val kind: TokKind, val text: String, val line: Int, val col: Int)

class Tokenizer(private val src: String) {
    private var i = 0
    private var line = 1
    private var col = 1
    val errors = mutableListOf<String>()

    private fun peek(): Char? = if (i < src.length) src[i] else null
    private fun advance(): Char {
        val c = src[i++]
        if (c == '\n') { line++; col = 1 } else col++
        return c
    }

    fun tokenize(): List<Token> {
        val out = mutableListOf<Token>()
        while (true) {
            val t = next()
            out.add(t)
            if (t.kind == TokKind.EOF) break
        }
        return out
    }

    private fun next(): Token {
        // 跳过空白与注释
        while (true) {
            when (peek()) {
                ' ', '\t', '\r', '\n' -> advance()
                '/' -> {
                    if (i + 1 < src.length && src[i + 1] == '/') {
                        while (peek() != null && peek() != '\n') advance()
                    } else if (i + 1 < src.length && src[i + 1] == '*') {
                        advance(); advance()
                        while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) advance()
                        if (i + 1 < src.length) { advance(); advance() }
                    } else return Token(TokKind.IDENT, "/", line, col).also { advance() }
                }
                else -> break
            }
        }
        val startLine = line; val startCol = col
        val c = peek() ?: return Token(TokKind.EOF, "", line, col)
        return when {
            c == '"' -> readString(startLine, startCol)
            c.isDigit() -> readNumber(startLine, startCol)
            c.isLetter() || c == '_' -> readIdent(startLine, startCol)
            c == '(' -> sym(TokKind.LPAREN, startLine, startCol)
            c == ')' -> sym(TokKind.RPAREN, startLine, startCol)
            c == '{' -> sym(TokKind.LBRACE, startLine, startCol)
            c == '}' -> sym(TokKind.RBRACE, startLine, startCol)
            c == '[' -> sym(TokKind.LBRACKET, startLine, startCol)
            c == ']' -> sym(TokKind.RBRACKET, startLine, startCol)
            c == ',' -> sym(TokKind.COMMA, startLine, startCol)
            c == '.' -> sym(TokKind.DOT, startLine, startCol)
            c == '=' -> sym(TokKind.EQ, startLine, startCol)
            c == ':' -> sym(TokKind.COLON, startLine, startCol)
            c == '@' -> sym(TokKind.AT, startLine, startCol)
            c == '<' -> sym(TokKind.LANGLE, startLine, startCol)
            c == '>' -> sym(TokKind.RANGLE, startLine, startCol)
            c == '*' -> sym(TokKind.STAR, startLine, startCol)
            c == '-' -> {
                // '->' 或负数
                if (i + 1 < src.length && src[i + 1] == '>') { advance(); advance(); Token(TokKind.ARROW, "->", startLine, startCol) }
                else if (i + 1 < src.length && src[i + 1].isDigit()) {
                    advance() // 消费 '-'
                    val num = readNumber(startLine, startCol)
                    Token(TokKind.NUMBER, "-" + num.text, startLine, startCol)
                }
                else { advance(); Token(TokKind.IDENT, "-", startLine, startCol) }
            }
            else -> {
                advance()
                errors.add("$startLine:$startCol 无法识别的字符 '$c'")
                Token(TokKind.IDENT, c.toString(), startLine, startCol)
            }
        }
    }

    private fun sym(k: TokKind, l: Int, c: Int): Token {
        advance(); return Token(k, k.name, l, c)
    }

    private fun readString(l: Int, c: Int): Token {
        val sb = StringBuilder()
        advance() // 开引号
        while (true) {
            val ch = peek() ?: break
            if (ch == '"') { advance(); break }
            if (ch == '\\') {
                advance()
                val esc = peek() ?: break
                sb.append(
                    when (esc) {
                        'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; '\\' -> '\\'; '"' -> '"'
                        else -> esc
                    }
                )
                advance()
            } else { sb.append(advance()) }
        }
        return Token(TokKind.STRING, sb.toString(), l, c)
    }

    private fun readNumber(l: Int, c: Int): Token {
        val sb = StringBuilder()
        while (peek()?.isDigit() == true) sb.append(advance())
        // 只有小数点后跟数字才视为小数（避免吞掉 20.sp 里的 .）
        if (peek() == '.' && i + 1 < src.length && src[i + 1].isDigit()) {
            sb.append(advance())
            while (peek()?.isDigit() == true) sb.append(advance())
        }
        return Token(TokKind.NUMBER, sb.toString(), l, c)
    }

    private fun readIdent(l: Int, c: Int): Token {
        val sb = StringBuilder()
        while (peek()?.let { it.isLetterOrDigit() || it == '_' } == true) sb.append(advance())
        return Token(TokKind.IDENT, sb.toString(), l, c)
    }
}
