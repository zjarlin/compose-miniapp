package compose.miniapp.compiler

// ---------- 编译诊断 ----------

class CompileException(val code: String, msg: String, val line: Int, val col: Int) :
    Exception("[$line:$col] $code $msg")

// ---------- 静态分析器（AST → IR） ----------
// 对应规格 §4（静态分析规则）与 §5/§6/§7（转译规则）。任何无法静态化的写法
// 一律抛出 CompileException（错误码），绝不静默降级。

object Analyzer {

    private val components = mapOf(
        "Column" to ComponentType.Column,
        "Row" to ComponentType.Row,
        "Text" to ComponentType.Text,
        "Button" to ComponentType.Button,
        "Image" to ComponentType.Image,
        "List" to ComponentType.List,
        "Input" to ComponentType.Input,
        "ScrollView" to ComponentType.ScrollView,
        "Spacer" to ComponentType.Spacer,
        "IfBlock" to ComponentType.IfBlock,
    )

    fun analyze(file: SourceFile): UiTree {
        val page = file.page
            ?: throw CompileException("E2001", "文件未声明 @EntryPoint 页面", 0, 0)
        // 状态 = 状态类字段 + 函数体内 remember 提升字段（标准 Compose 写法）
        val allFields = page.stateClassFields + page.bodyStateFields
        val stateNames = allFields.map { it.name }.toSet()
        val handlers = mutableListOf<EventHandler>()
        val root = buildRoot(page, stateNames, handlers)
        return UiTree(
            pagePath = page.pagePath,
            pageName = page.pagePath.substringAfterLast("/"),
            root = root,
            stateFields = allFields,
            eventHandlers = handlers,
        )
    }

    private fun buildRoot(page: PageModel, stateNames: Set<String>, handlers: MutableList<EventHandler>): UiNode {
        // E1008：UI 组合函数内直接改状态（含 if 嵌套与 remember 裸名状态）
        walkAssigns(page.body, page, stateNames)
        // 顶层单根
        return if (page.body.size == 1 && page.body[0] is Stmt.Expr) {
            val call = (page.body[0] as Stmt.Expr).call
            if (call.receiver != null) {
                throw CompileException("E1012", "页面顶层必须是单个根组件", call.line, call.col)
            }
            buildNode(call, page, stateNames, null, "page", handlers, 0, 0)
        } else {
            // 多语句顶层：自动包一层隐式 Column 容器
            val synthetic = Stmt.Call(
                null, "Column", listOf(NamedArg("spacing", Value.VUnit(0.0, "dp"))),
                emptyList(), page.body, page.body.firstOrNull()?.let { 0 } ?: 0, 0,
            )
            buildNode(synthetic, page, stateNames, null, "page", handlers, 0, 0)
        }
    }

    private fun buildNode(
        call: Stmt.Call,
        page: PageModel,
        stateNames: Set<String>,
        loop: LoopContext?,
        parentId: String,
        handlers: MutableList<EventHandler>,
        index: Int,
        depth: Int,
    ): UiNode {
        if (call.receiver != null) {
            throw CompileException("E1002", "未知组件 '${call.receiver}.${call.name}'（白名单：${components.keys.sorted()}）", call.line, call.col)
        }
        // 标准 Compose：LazyColumn { items(list, key = {...}) { item -> ... } } → List
        if (call.name == "LazyColumn") {
            if (call.body.size != 1 || call.body[0] !is Stmt.Expr) {
                throw CompileException("E1001", "LazyColumn 块内必须恰好一个 items(list) { item -> ... }", call.line, call.col)
            }
            val ic = (call.body[0] as Stmt.Expr).call
            if (ic.receiver != null || ic.name != "items") {
                throw CompileException("E1001", "LazyColumn 块内只支持 items(...) 调用", call.line, call.col)
            }
            val listArg = ic.args.firstOrNull { it.name == null }?.let { NamedArg("items", it.value) }
                ?: throw CompileException("E1001", "items 缺少列表参数", call.line, call.col)
            val keyArg = ic.args.firstOrNull { it.name == "key" }
            val rewritten = Stmt.Call(
                null, "List", listOfNotNull(listArg, keyArg), ic.lambdaParams, ic.body, call.line, call.col,
            )
            return buildNode(rewritten, page, stateNames, loop, parentId, handlers, index, depth)
        }
        val type = components[call.name]
            ?: throw CompileException(
                "E1002", "组件 '${call.name}' 不在白名单（${components.keys.sorted().joinToString("/")}）；动态组件或未注册组件不允许", call.line, call.col
            )
        val nodeId = stableHash("${page.pagePath}|${type.name}|$parentId|$index")

        // 块参数合法性统一检查（防静默丢弃）
        val acceptsBlock = type in setOf(
            ComponentType.Column, ComponentType.Row, ComponentType.List,
            ComponentType.ScrollView, ComponentType.IfBlock,
        )
        // Button 的块是 content（转 label，由 Button 分支消费）；其余非容器组件不允许块
        if (!acceptsBlock && type != ComponentType.Button && call.body.isNotEmpty()) {
            throw CompileException("E1006", "${call.name} 不接受块参数（{ ... }）", call.line, call.col)
        }
        if (acceptsBlock && type != ComponentType.List && call.lambdaParams.isNotEmpty()) {
            throw CompileException("E1006", "${call.name} 的块不接受 lambda 参数（仅 List 支持 item ->）", call.line, call.col)
        }

        val node = buildNodeInner(call, type, nodeId, page, stateNames, loop, handlers, depth)
        return node
    }

    private fun buildNodeInner(
        call: Stmt.Call,
        type: ComponentType,
        nodeId: String,
        page: PageModel,
        stateNames: Set<String>,
        loop: LoopContext?,
        handlers: MutableList<EventHandler>,
        depth: Int,
    ): UiNode {
        val props = mutableListOf<Prop>()
        val styleDecls = mutableListOf<Pair<String, String>>()
        styleDecls.addAll(baseStyleDecls(type))
        val events = mutableListOf<EventBinding>()
        var children = emptyList<UiNode>()
        var loopCtx: LoopContext? = null
        var spacingRpx: Double? = null
        var bodyConsumed = false   // 组件分支已消费块内容（如 Button content → label）

        fun arg(name: String): NamedArg? = call.args.firstOrNull { it.name == name }
        fun positional(i: Int): NamedArg? = call.args.filter { it.name == null }.getOrNull(i)

        when (type) {
            ComponentType.Column -> {
                for (a in call.args) {
                    when (a.name) {
                        "padding" -> styleDecls.add("padding" to unitRpx(a.value, call))
                        "spacing" -> spacingRpx = unitRpx(a.value, call).substringBefore("rpx").toDouble()
                        "modifier" -> styleDecls.addAll(extractModifier(a.value, call))
                        "verticalArrangement" -> {
                            val css = arrangementCss(a.value, call, vertical = true)
                            if (css != null) styleDecls.add(css) else spacingRpx = arrangementSpacing(a.value, call)
                        }
                        else -> if (a.name != null) throw CompileException("E1001", "Column 不支持参数 '${a.name}'（支持 padding/spacing/modifier/verticalArrangement）", call.line, call.col)
                    }
                }
                if (call.lambdaParams.isNotEmpty()) throw CompileException("E1006", "Column 块不接受 lambda 参数", call.line, call.col)
            }
            ComponentType.Row -> {
                for (a in call.args) {
                    when (a.name) {
                        "padding" -> styleDecls.add("padding" to unitRpx(a.value, call))
                        "align" -> styleDecls.add("align-items" to alignmentVal(a.value, call))
                        "spacing" -> spacingRpx = unitRpx(a.value, call).substringBefore("rpx").toDouble()
                        "modifier" -> styleDecls.addAll(extractModifier(a.value, call))
                        "horizontalArrangement" -> {
                            val css = arrangementCss(a.value, call, vertical = false)
                            if (css != null) styleDecls.add(css) else spacingRpx = arrangementSpacing(a.value, call)
                        }
                        else -> if (a.name != null) throw CompileException("E1001", "Row 不支持参数 '${a.name}'（支持 padding/spacing/align/modifier/horizontalArrangement）", call.line, call.col)
                    }
                }
            }
            ComponentType.Text -> {
                val textArg = arg("text") ?: positional(0)
                if (textArg == null) throw CompileException("E1001", "Text 缺少 text 参数", call.line, call.col)
                props.add(evalProp(textArg.value, page, stateNames, loop, call))
                for (a in call.args) {
                    if (a.name == null || a.name == "text") continue
                    when (a.name) {
                        "fontSize" -> styleDecls.add("font-size" to unitRpx(a.value, call))
                        "fontWeight" -> styleDecls.add("font-weight" to fontWeightVal(a.value, call))
                        "color" -> styleDecls.add("color" to colorVal(a.value, call))
                        "textAlign" -> styleDecls.add("text-align" to textAlignVal(a.value, call))
                        "modifier" -> styleDecls.addAll(extractModifier(a.value, call))
                        "style" -> {
                            val ts = a.value as? Value.VCall
                                ?: throw CompileException("E1001", "Text 的 style 必须是 TextStyle(...)", call.line, call.col)
                            if (ts.name != "TextStyle") throw CompileException("E1001", "未知样式构造 '${ts.name}'", call.line, call.col)
                            for (sa in ts.args) {
                                when (sa.name) {
                                    "fontSize" -> styleDecls.add("font-size" to unitRpx(sa.value, call))
                                    "bold" -> if (sa.value is Value.VBool && sa.value.v) styleDecls.add("font-weight" to "bold")
                                    "color" -> styleDecls.add("color" to colorVal(sa.value, call))
                                    else -> throw CompileException("E1001", "TextStyle 不支持 '${sa.name}'", call.line, call.col)
                                }
                            }
                        }
                        else -> throw CompileException("E1001", "Text 不支持参数 '${a.name}'（支持 text/fontSize/fontWeight/color/style）", call.line, call.col)
                    }
                }
                if (call.lambdaParams.isNotEmpty()) throw CompileException("E1006", "Text 不接受块参数", call.line, call.col)
            }
            ComponentType.Button -> {
                val label = arg("label") ?: positional(0)
                var labelValue: Value? = label?.value
                if (labelValue == null) {
                    // 标准 Compose：Button(onClick = {...}) { Text("x") } → content 提取为 label
                    if (call.body.size == 1) {
                        val inner = call.body[0]
                        val innerCall = (inner as? Stmt.Expr)?.call
                        if (innerCall != null && innerCall.receiver == null && innerCall.name == "Text") {
                            val it2 = innerCall.args.firstOrNull { a -> a.name == "text" }
                                ?: innerCall.args.firstOrNull { a -> a.name == null }
                            if (it2 == null) throw CompileException("E1001", "Button content 中的 Text 缺少 text", call.line, call.col)
                            labelValue = it2.value
                            bodyConsumed = true
                        }
                    }
                    if (labelValue == null) {
                        throw CompileException("E1001", "Button 缺少 label/text 内容（支持 Button(\"x\", onClick=...) 或 Button(onClick=...) { Text(\"x\") }）", call.line, call.col)
                    }
                }
                props.add(evalProp(labelValue!!, page, stateNames, loop, call))
                for (a in call.args) {
                    when (a.name) {
                        null, "label", "onClick" -> Unit
                        "modifier" -> styleDecls.addAll(extractModifier(a.value, call))
                        else -> throw CompileException("E1001", "Button 不支持参数 '${a.name}'（支持 label/onClick/modifier）", call.line, call.col)
                    }
                }
                val onClick = arg("onClick")
                if (onClick != null) {
                    val block = onClick.value as? Value.VBlock
                        ?: throw CompileException("E1001", "Button 的 onClick 必须是 lambda", call.line, call.col)
                    val handlerId = translateEvent(block, "click", nodeId, page, stateNames, loop, handlers, call)
                    events.add(EventBinding("bindtap", handlerId))
                }
            }
            ComponentType.Image -> {
                val url = arg("url") ?: arg("src")
                if (url == null) throw CompileException("E1001", "Image 缺少 url/src 参数", call.line, call.col)
                props.add(evalProp(url.value, page, stateNames, loop, call))
                val mode = arg("mode")
                if (mode != null) props.add(Prop.Literal("mode:" + strVal(mode.value, call)))
                val lazy = arg("lazyLoad")
                if (lazy != null) props.add(Prop.Bool((lazy.value as? Value.VBool)?.v ?: throw CompileException("E1001", "lazyLoad 必须是布尔", call.line, call.col)))
                for (a in call.args) {
                    if (a.name == "modifier") styleDecls.addAll(extractModifier(a.value, call))
                }
            }
            ComponentType.List -> {
                val items = arg("items")
                    ?: throw CompileException("E1001", "List 缺少 items 参数（必须是状态字段）", call.line, call.col)
                val itemsRef = items.value as? Value.VRef
                    ?: throw CompileException("E1001", "List 的 items 必须是状态字段引用（如 state.items）", call.line, call.col)
                val field = itemsRef.parts.last()
                if (itemsRef.parts.size > 1 && itemsRef.parts.first() != page.stateParam) {
                    throw CompileException("E1001", "List 的 items 必须指向页面状态字段", call.line, call.col)
                }
                if (field !in stateNames) {
                    throw CompileException("E1001", "List 引用未注册的状态字段 '$field'", call.line, call.col)
                }
                if ((page.stateClassFields + page.bodyStateFields).first { it.name == field }.kind != "LIST") {
                    throw CompileException("E1001", "List 的 '$field' 不是列表状态（需 mutableStateOf(listOf(...)) 声明）", call.line, call.col)
                }
                var keyField: String? = null
                val key = arg("key")
                if (key != null) {
                    val kb = key.value as? Value.VBlock
                        ?: throw CompileException("E1001", "List 的 key 必须是 { it.xxx } lambda", call.line, call.col)
                    keyField = extractItField(kb)
                }
                val itemVar = call.lambdaParams.firstOrNull()
                    ?: throw CompileException("E1006", "List 必须提供 item 块参数（{ item -> ... }）", call.line, call.col)
                for (a in call.args) {
                    if (a.name == "modifier") styleDecls.addAll(extractModifier(a.value, call))
                }
                loopCtx = LoopContext(field, itemVar, keyField)
            }
            ComponentType.Input -> {
                val value = arg("value")
                    ?: throw CompileException("E1001", "Input 缺少 value 参数（状态字段）", call.line, call.col)
                val vp = evalProp(value.value, page, stateNames, loop, call)
                if (vp !is Prop.StateRef) throw CompileException("E1001", "Input 的 value 必须是状态字段（如 state.input）", call.line, call.col)
                props.add(vp)
                val ph = arg("placeholder")
                if (ph != null) props.add(Prop.Literal("placeholder:" + strVal(ph.value, call)))
                val onInput = arg("onValueChange") ?: arg("onInput")
                if (onInput != null) {
                    val blk = onInput.value as? Value.VBlock
                        ?: throw CompileException("E1001", "Input 的 onValueChange/onInput 必须是 lambda", call.line, call.col)
                    for (sb in blk.body) {
                        if (sb is Stmt.Assign) {
                            if (sb.target.parts.last() != vp.field) {
                                throw CompileException("E1009", "onValueChange 内只能给自身字段 '${vp.field}' 赋值", sb.line, sb.col)
                            }
                        } else if (sb !is Stmt.Expr) {
                            throw CompileException("E1009", "onValueChange 内只支持给自身字段赋值", (sb as? Stmt.Call)?.line ?: 0, (sb as? Stmt.Call)?.col ?: 0)
                        }
                    }
                    val handlerId = "h_${nodeId}_input"
                    handlers.add(EventHandler(handlerId, EventKind.INPUT_COMMIT, listOf("this.__commit(\"${vp.field}\", e.detail.value);")))
                    events.add(EventBinding("bindinput", handlerId))
                }
                for (a in call.args) {
                    if (a.name == "modifier") styleDecls.addAll(extractModifier(a.value, call))
                }
            }
            ComponentType.ScrollView -> {
                val sy = arg("scrollY")
                if (sy != null) {
                    if (sy.value !is Value.VBool) throw CompileException("E1001", "scrollY 必须是布尔", call.line, call.col)
                    if ((sy.value as Value.VBool).v) props.add(Prop.Literal("scroll-y"))
                }
                for (a in call.args) {
                    if (a.name == "modifier") styleDecls.addAll(extractModifier(a.value, call))
                }
            }
            ComponentType.Spacer -> {
                for (a in call.args) {
                    when (a.name) {
                        "width" -> styleDecls.add("width" to unitRpx(a.value, call))
                        "height" -> styleDecls.add("height" to unitRpx(a.value, call))
                        "modifier" -> styleDecls.addAll(extractModifier(a.value, call))
                        else -> if (a.name != null) throw CompileException("E1001", "Spacer 不支持 '${a.name}'", call.line, call.col)
                    }
                }
            }
            ComponentType.IfBlock -> {
                val cond = arg("condition") ?: positional(0)
                    ?: throw CompileException("E1001", "IfBlock 缺少 condition（状态字段）", call.line, call.col)
                val cr = cond.value as? Value.VRef
                    ?: throw CompileException("E1001", "IfBlock 的 condition 必须是状态字段", call.line, call.col)
                val field = cr.parts.last()
                if (field !in stateNames) throw CompileException("E1001", "IfBlock 引用未注册状态字段 '$field'", call.line, call.col)
                props.add(Prop.StateRef(field))
                for (a in call.args) {
                    if (a.name == "modifier") styleDecls.addAll(extractModifier(a.value, call))
                }
            }
        }

        // 子节点（块 body；Button 已消费 content 时不生成 children）
        val blockBody = if (bodyConsumed) emptyList() else call.body
        if (blockBody.isNotEmpty()) {
            val childDepth = depth + 1
            if (childDepth > 12) throw CompileException("E1005", "组件嵌套过深（>12）", call.line, call.col)
            children = blockBody.mapIndexed { i, s ->
                when (s) {
                    is Stmt.Expr -> buildNode(s.call, page, stateNames, loopCtx ?: loop, nodeId, handlers, i, childDepth)
                    is Stmt.If -> {
                        // 标准 Compose if 语句 → IfBlock 组件
                        val synthetic = Stmt.Call(
                            null, "IfBlock", listOf(NamedArg("condition", s.cond)),
                            emptyList(), s.body, s.line, s.col,
                        )
                        buildNode(synthetic, page, stateNames, loopCtx ?: loop, nodeId, handlers, i, childDepth)
                    }
                    else -> {
                        val l = (s as? Stmt.Call)?.line ?: (s as? Stmt.Assign)?.line ?: (s as? Stmt.If)?.line ?: 0
                        val c = (s as? Stmt.Call)?.col ?: (s as? Stmt.Assign)?.col ?: (s as? Stmt.If)?.col ?: 0
                        throw CompileException("E1006", "块内只允许组件调用 / if 条件渲染，不允许 ${s.javaClass.simpleName}", l, c)
                    }
                }
            }
        }

        // spacing：注入每个子节点 margin-top/left（除第一个）；类名由节点 id 派生，避免默认类名覆盖
        if (spacingRpx != null && spacingRpx > 0) {
            children = children.mapIndexed { i, c ->
                if (i == 0) c
                else {
                    val decl = if (type == ComponentType.Row) "margin-left" to "${fmt(spacingRpx)}rpx"
                    else "margin-top" to "${fmt(spacingRpx)}rpx"
                    val cls = "${c.type.classPrefix}${c.id.take(6)}"
                    c.copy(style = StyleNode(cls, (c.style?.declarations ?: emptyList()) + decl))
                }
            }
        }

        val style = if (styleDecls.isEmpty()) null
        else StyleNode("${type.classPrefix}${nodeId.take(6)}", styleDecls)

        return UiNode(nodeId, type, props, style, events, children, loopCtx ?: loop)
    }

    // ---------- 工具 ----------

    // E1008 递归检查：UI 组合函数（含 if 嵌套）内不得直接改状态
    private fun walkAssigns(stmts: List<Stmt>, page: PageModel, stateNames: Set<String>) {
        for (s in stmts) {
            if (s is Stmt.Assign) {
                val parts = s.target.parts
                val isState = (parts.size == 1 && parts[0] in stateNames) ||
                    (parts.size == 2 && parts[0] == page.stateParam)
                if (isState) {
                    throw CompileException(
                        "E1008", "UI 组合函数内不允许直接修改状态（${parts.joinToString(".")}），" +
                            "请把状态变更移到事件回调或逻辑层", s.line, s.col
                    )
                }
            } else if (s is Stmt.If) {
                walkAssigns(s.body, page, stateNames)
            }
        }
    }

    // 标准 Compose Modifier 链 → WXSS 声明
    private fun extractModifier(v: Value, call: Stmt.Call): List<Pair<String, String>> {
        // 单层 Modifier.padding(...) 解析为 VCall；多层为 VChain
        val chain: List<ChainCall> = when (v) {
            is Value.VChain -> v.calls
            is Value.VCall -> if (v.receiver == "Modifier") listOf(ChainCall("Modifier", v.name, v.args))
            else throw CompileException("E1001", "modifier 必须是 Modifier 链（如 Modifier.padding(16.dp).fillMaxWidth()）", call.line, call.col)
            else -> throw CompileException("E1001", "modifier 必须是 Modifier 链（如 Modifier.padding(16.dp).fillMaxWidth()）", call.line, call.col)
        }
        val out = mutableListOf<Pair<String, String>>()
        for (cc in chain) {
            when (cc.name) {
                "fillMaxWidth" -> out.add("width" to "100%")
                "fillMaxHeight" -> out.add("height" to "100%")
                "width" -> out.add("width" to unitRpx(ccArg(cc, call), call))
                "height" -> out.add("height" to unitRpx(ccArg(cc, call), call))
                "padding" -> {
                    val h = cc.args.firstOrNull { it.name == "horizontal" }?.value
                    val vt = cc.args.firstOrNull { it.name == "vertical" }?.value
                    val all = cc.args.firstOrNull { it.name == null }?.value
                    when {
                        h != null -> { out.add("padding-left" to unitRpx(h, call)); out.add("padding-right" to unitRpx(h, call)) }
                        vt != null -> { out.add("padding-top" to unitRpx(vt, call)); out.add("padding-bottom" to unitRpx(vt, call)) }
                        all != null -> out.add("padding" to unitRpx(all, call))
                        else -> throw CompileException("E1001", "padding 需要尺寸参数（padding(16.dp) / padding(horizontal=.., vertical=..)）", call.line, call.col)
                    }
                }
                "weight" -> {
                    val f = ccArg(cc, call)
                    if (f is Value.VNum) out.add("flex" to fmt(f.v))
                    else throw CompileException("E1001", "weight 参数必须是数字（weight(1f)）", call.line, call.col)
                }
                "background" -> out.add("background-color" to colorVal(ccArg(cc, call), call))
                "borderRadius" -> out.add("border-radius" to unitRpx(ccArg(cc, call), call))
                "margin" -> {
                    val all = cc.args.firstOrNull { it.name == null }?.value
                    val h = cc.args.firstOrNull { it.name == "horizontal" }?.value
                    val vt = cc.args.firstOrNull { it.name == "vertical" }?.value
                    when {
                        h != null -> { out.add("margin-left" to unitRpx(h, call)); out.add("margin-right" to unitRpx(h, call)) }
                        vt != null -> { out.add("margin-top" to unitRpx(vt, call)); out.add("margin-bottom" to unitRpx(vt, call)) }
                        all != null -> out.add("margin" to unitRpx(all, call))
                        else -> throw CompileException("E1001", "margin 需要尺寸参数", call.line, call.col)
                    }
                }
                "border" -> {
                    val width = cc.args.firstOrNull { it.name == "width" }?.value ?: ccArg(cc, call)
                    val color = cc.args.firstOrNull { it.name == "color" }?.value
                    if (color == null) throw CompileException("E1001", "border 需要 color 参数", call.line, call.col)
                    out.add("border" to "${unitRpx(width, call)} solid ${colorVal(color, call)}")
                }
                else -> throw CompileException("E1001", "Modifier.${cc.name} 不支持（白名单：fillMaxWidth/fillMaxHeight/width/height/padding/margin/weight/background/borderRadius/border）", call.line, call.col)
            }
        }
        return out
    }

    private fun ccArg(cc: ChainCall, call: Stmt.Call): Value =
        cc.args.firstOrNull { it.name == null }?.value
            ?: throw CompileException("E1001", "Modifier.${cc.name} 缺少位置参数", call.line, call.col)

    // Arrangement.spacedBy(12.dp) → 间距 rpx；其他 Arrangement 常量 → null（无间距）
    private fun arrangementSpacing(v: Value, call: Stmt.Call): Double? {
        if (v is Value.VCall && v.receiver == "Arrangement" && v.name == "spacedBy") {
            val a = v.args.firstOrNull { it.name == null }?.value
                ?: throw CompileException("E1001", "Arrangement.spacedBy 需要距离参数", call.line, call.col)
            return unitRpx(a, call).substringBefore("rpx").toDouble()
        }
        if (v is Value.VRef && v.parts.first() == "Arrangement") return null
        throw CompileException("E1001", "verticalArrangement/horizontalArrangement 仅支持 Arrangement.spacedBy(...)（或省略）", call.line, call.col)
    }

    private fun arrangementCss(v: Value, call: Stmt.Call, vertical: Boolean): Pair<String, String>? {
        if (v is Value.VRef && v.parts.size == 2 && v.parts[0] == "Arrangement") {
            return when (v.parts[1]) {
                "SpaceBetween" -> "justify-content" to "space-between"
                "Center" -> "justify-content" to "center"
                "End" -> "justify-content" to "flex-end"
                else -> throw CompileException("E1001", "Arrangement.${v.parts[1]} 未映射（SpaceBetween/Center/End）", call.line, call.col)
            }
        }
        arrangementSpacing(v, call)
        return null
    }

    private fun alignmentVal(v: Value, call: Stmt.Call): String {
        if (v is Value.VRef && v.parts.size == 2 && v.parts[0] == "Alignment") {
            return when (v.parts[1]) {
                "Center" -> "center"
                "Start" -> "flex-start"
                "End" -> "flex-end"
                else -> throw CompileException("E1001", "Alignment.${v.parts[1]} 未映射（Center/Start/End）", call.line, call.col)
            }
        }
        throw CompileException("E1001", "align 必须是 Alignment.Center/Start/End", call.line, call.col)
    }

    private fun textAlignVal(v: Value, call: Stmt.Call): String {
        return when (v) {
            is Value.VStr -> v.v
            is Value.VRef -> if (v.parts.size == 2 && v.parts[0] == "TextAlign") {
                when (v.parts[1]) {
                    "Center" -> "center"
                    "Start" -> "left"
                    "End" -> "right"
                    else -> throw CompileException("E1001", "TextAlign.${v.parts[1]} 未映射（Center/Start/End）", call.line, call.col)
                }
            } else throw CompileException("E1001", "textAlign 必须是 TextAlign.Center/Start/End", call.line, call.col)
            else -> throw CompileException("E1001", "textAlign 必须是 TextAlign.Center/Start/End", call.line, call.col)
        }
    }

    private fun baseStyleDecls(type: ComponentType): List<Pair<String, String>> = when (type) {
        ComponentType.Column -> listOf("display" to "flex", "flex-direction" to "column")
        ComponentType.Row -> listOf("display" to "flex", "flex-direction" to "row", "align-items" to "center")
        ComponentType.ScrollView -> listOf("display" to "block")
        else -> emptyList()
    }

    private fun fontWeightVal(v: Value, call: Stmt.Call): String {
        return when (v) {
            is Value.VStr -> v.v
            is Value.VRef -> {
                if (v.parts.size == 2 && v.parts[0] == "FontWeight") {
                    when (v.parts[1]) {
                        "Bold" -> "bold"; "Normal" -> "normal"; "Light" -> "300"; "Medium" -> "500"; "SemiBold" -> "600"
                        else -> throw CompileException("E1001", "FontWeight.${v.parts[1]} 未映射（Bold/Normal/Light/Medium/SemiBold）", call.line, call.col)
                    }
                } else throw CompileException("E1001", "fontWeight 必须是 FontWeight.Bold 等常量", call.line, call.col)
            }
            else -> throw CompileException("E1001", "fontWeight 必须是 FontWeight.Bold 等常量", call.line, call.col)
        }
    }

    private fun colorVal(v: Value, call: Stmt.Call): String {
        return when (v) {
            is Value.VStr -> v.v
            is Value.VRef -> {
                if (v.parts.size == 2 && v.parts[0] == "Color") {
                    when (v.parts[1]) {
                        "Red" -> "#ff0000"; "Blue" -> "#2196f3"; "Green" -> "#4caf50"
                        "Black" -> "#000000"; "White" -> "#ffffff"; "Gray" -> "#9e9e9e"; "Orange" -> "#ff9800"
                        else -> throw CompileException("E1001", "Color.${v.parts[1]} 未映射（Red/Blue/Green/Black/White/Gray/Orange）", call.line, call.col)
                    }
                } else throw CompileException("E1001", "color 必须是 \"#rrggbb\" 或 Color.X 常量", call.line, call.col)
            }
            else -> throw CompileException("E1001", "color 必须是 \"#rrggbb\" 或 Color.X 常量", call.line, call.col)
        }
    }

    private fun mergeStyle(style: StyleNode?, decl: Pair<String, String>): StyleNode {
        val base = style?.declarations ?: emptyList()
        return StyleNode(style?.className ?: "node", base + decl)
    }

    private fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun unitRpx(v: Value, call: Stmt.Call): String {
        return when (v) {
            is Value.VUnit -> when (v.unit) {
                "dp", "sp", "rpx" -> "${fmt(v.v * 2)}rpx"   // designWidth=750: 1dp=2rpx, 1sp=2rpx
                "px" -> "${fmt(v.v)}rpx"
                else -> throw CompileException("E1001", "不支持的单位 '${v.unit}'（仅 dp/sp/px/rpx）", call.line, call.col)
            }
            is Value.VNum -> "${fmt(v.v)}rpx"
            else -> throw CompileException("E1001", "样式值必须是带单位数字（如 16.dp / 20.sp）", call.line, call.col)
        }
    }

    private fun strVal(v: Value, call: Stmt.Call): String {
        return (v as? Value.VStr)?.v
            ?: throw CompileException("E1001", "期望字符串字面量", call.line, call.col)
    }

    private fun evalProp(v: Value, page: PageModel, stateNames: Set<String>, loop: LoopContext?, call: Stmt.Call): Prop {
        return when (v) {
            is Value.VStr -> Prop.Literal(v.v)
            is Value.VNum -> Prop.Num(v.v)
            is Value.VBool -> Prop.Bool(v.v)
            is Value.VUnit -> throw CompileException("E1001", "带单位的值不能作为文本/数据参数（仅样式用）", call.line, call.col)
            is Value.VRef -> {
                val head = v.parts.first()
                if (v.parts.size == 1 && head in stateNames) {
                    Prop.StateRef(head)   // remember 提升的裸名状态（标准 Compose 写法）
                } else if (head == page.stateParam && v.parts.size == 2) {
                    val field = v.parts[1]
                    if (field in stateNames) Prop.StateRef(field)
                    else throw CompileException("E1001", "引用未注册状态字段 '$field'（${page.stateClassFields.map { it.name }}）", call.line, call.col)
                } else if (loop != null && head == loop.itemVar && v.parts.size == 2) {
                    Prop.LoopVar(loop.itemVar, v.parts[1])
                } else {
                    throw CompileException(
                        "E1001", "参数无法静态求值：'${v.parts.joinToString(".")}'（仅支持字面量、状态字段 state.xxx、循环变量 item.xxx）",
                        call.line, call.col
                    )
                }
            }
            is Value.VBlock -> throw CompileException("E1006", "此处不允许 lambda（只允许事件参数/List.key）", call.line, call.col)
            is Value.VCall -> throw CompileException("E1001", "此处不允许构造调用 '${v.name}'", call.line, call.col)
            is Value.VChain -> throw CompileException("E1001", "此处不允许 Modifier 链（仅 style/modifier 参数可用）", call.line, call.col)
        }
    }

    private fun extractItField(block: Value.VBlock): String {
        if (block.body.size != 1) throw CompileException("E1001", "List.key 必须是 { it.xxx } 单表达式", 0, 0)
        val s = block.body[0]
        if (s !is Stmt.Expr || s.call.receiver == null) throw CompileException("E1001", "List.key 必须是 { it.xxx }", 0, 0)
        return s.call.name
    }

    // 事件 lambda 转译（§7.2）
    private fun translateEvent(
        block: Value.VBlock,
        eventName: String,
        nodeId: String,
        page: PageModel,
        stateNames: Set<String>,
        loop: LoopContext?,
        handlers: MutableList<EventHandler>,
        call: Stmt.Call,
    ): String {
        val lines = mutableListOf<String>()
        var kind = EventKind.COMBINED
        for (s in block.body) {
            when (s) {
                is Stmt.Expr -> {
                    val c = s.call
                    when {
                        c.receiver == page.scopeParam && c.name == "navigateTo" && c.args.size == 1 -> {
                            val path = (c.args[0].value as? Value.VStr)?.v
                                ?: throw CompileException("E1009", "navigateTo 参数必须是字符串字面量", c.line, c.col)
                            lines.add("wx.navigateTo({ url: \"$path\" });")
                            if (kind == EventKind.COMBINED) kind = EventKind.NAVIGATE
                        }
                        c.receiver == null && c.name == "nav" && c.args.size == 1 -> {
                            // 标准 Compose 风格导航别名
                            val path = (c.args[0].value as? Value.VStr)?.v
                                ?: throw CompileException("E1009", "nav 参数必须是字符串字面量", c.line, c.col)
                            lines.add("wx.navigateTo({ url: \"$path\" });")
                            if (kind == EventKind.COMBINED) kind = EventKind.NAVIGATE
                        }
                        c.receiver == page.scopeParam && c.name == "navigateBack" && c.args.isEmpty() -> {
                            lines.add("wx.navigateBack();")
                            if (kind == EventKind.COMBINED) kind = EventKind.NAVIGATE
                        }
                        c.receiver == null && c.name == "navBack" && c.args.isEmpty() -> {
                            lines.add("wx.navigateBack();")
                            if (kind == EventKind.COMBINED) kind = EventKind.NAVIGATE
                        }
                        c.receiver == "logic" -> {
                            val argText = c.args.joinToString(", ") { jsValue(it.value, page, stateNames, loop, call) }
                            lines.add("logic.${c.name}($argText);")
                            kind = EventKind.LOGIC_CALL
                        }
                        c.receiver == page.stateParam -> {
                            throw CompileException("E1009", "事件内调用状态方法 '${c.name}' 不支持（V1），请改为直接赋值或调 logic.*", c.line, c.col)
                        }
                        else -> throw CompileException("E1009", "事件 lambda 内不支持调用 '${c.receiver?.let { "$it." } ?: ""}${c.name}'", c.line, c.col)
                    }
                }
                is Stmt.Assign -> {
                    val parts = s.target.parts
                    val isStateAssign = (parts.size == 2 && parts[0] == page.stateParam) ||
                        (parts.size == 1 && parts[0] in stateNames)
                    if (isStateAssign) {
                        val field = parts.last()
                        lines.add("this.__commit(\"$field\", ${jsValue(s.value, page, stateNames, loop, call)});")
                        if (kind == EventKind.COMBINED) kind = EventKind.COMMIT
                    } else {
                        throw CompileException("E1009", "事件内只允许给状态字段赋值（state.xxx = ... / 裸名状态 = ...）", s.line, s.col)
                    }
                }
                else -> {
                    val l = (s as? Stmt.Call)?.line ?: (s as? Stmt.Assign)?.line ?: 0
                    val c = (s as? Stmt.Call)?.col ?: (s as? Stmt.Assign)?.col ?: 0
                    throw CompileException("E1009", "事件 lambda 内不支持此语句", l, c)
                }
            }
        }
        if (lines.isEmpty()) throw CompileException("E1009", "事件 lambda 为空或无法转译", call.line, call.col)
        val handlerId = "h_${nodeId}_$eventName"
        handlers.add(EventHandler(handlerId, kind, lines))
        return handlerId
    }

    private fun jsValue(v: Value, page: PageModel, stateNames: Set<String>, loop: LoopContext?, call: Stmt.Call): String {
        return when (v) {
            is Value.VStr -> "\"${v.v}\""
            is Value.VNum -> v.v.toString()
            is Value.VBool -> v.v.toString()
            is Value.VUnit -> fmt(v.v)
            is Value.VRef -> {
                if (v.parts.size == 2 && v.parts[0] == page.stateParam) "this.data.${v.parts[1]}"
                else if (v.parts.size == 1 && v.parts[0] in stateNames) "this.data.${v.parts[0]}"
                else if (loop != null && v.parts.size == 2 && v.parts[0] == loop.itemVar) {
                    // 循环内 item.x：通过 WXML data-index 传回当前行
                    "this.data.${loop.itemsField}[e.currentTarget.dataset.index].${v.parts[1]}"
                }
                else throw CompileException("E1009", "事件内无法转译引用 '${v.parts.joinToString(".")}'", call.line, call.col)
            }
            else -> throw CompileException("E1009", "事件内不支持的值类型", call.line, call.col)
        }
    }
}
