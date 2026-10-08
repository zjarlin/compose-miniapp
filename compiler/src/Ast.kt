package compose.miniapp.compiler

// ---------- AST ----------

sealed class Value {
    data class VStr(val v: String) : Value()
    data class VNum(val v: Double) : Value()
    data class VBool(val v: Boolean) : Value()
    // 带单位的数字：16.dp / 20.sp / 10.px
    data class VUnit(val v: Double, val unit: String) : Value()
    // 属性引用链：state.items / item.name / scope.x / input（remember 提升状态裸名）
    data class VRef(val parts: List<String>) : Value()
    // 内联 lambda 块（事件 / List.key）
    data class VBlock(val params: List<String>, val body: List<Stmt>) : Value()
    // 构造式调用（值位置）：TextStyle(fontSize = 20.sp, bold = true)
    data class VCall(val receiver: String?, val name: String, val args: List<NamedArg>) : Value()
    // 链式调用：Modifier.fillMaxWidth().padding(16.dp)
    data class VChain(val calls: List<ChainCall>) : Value()
}

data class ChainCall(val receiver: String?, val name: String, val args: List<NamedArg>)

data class NamedArg(val name: String?, val value: Value)

sealed class Stmt {
    // 组件/函数调用：Button("x", onClick = { ... }) { ... }
    data class Call(
        val receiver: String?,           // scope / state / logic / null（组件）
        val name: String,
        val args: List<NamedArg>,
        val lambdaParams: List<String>,   // 块 lambda 参数名（如 item ->）
        val body: List<Stmt>,
        val line: Int, val col: Int,
    ) : Stmt()

    // 赋值：state.loading = true / item.x = 1
    data class Assign(val target: Value.VRef, val value: Value, val line: Int, val col: Int) : Stmt()

    // 表达式语句
    data class Expr(val call: Call) : Stmt()

    // 函数体内状态声明：var x by remember { mutableStateOf(init) } / val x = remember {...}
    data class StateDecl(val field: StateField, val line: Int, val col: Int) : Stmt()

    // 条件渲染语句：if (cond) { ... }
    data class If(val cond: Value, val body: List<Stmt>, val line: Int, val col: Int) : Stmt()
}

data class StateField(
    val name: String,
    val defaultRaw: String,      // JS 可嵌入的初始值文本："false" / "0" / "\"abc\"" / "[]"
    val kind: String,            // VALUE / LIST（默认值为 listOf 形态时判为 LIST）
)

data class PageModel(
    val pagePath: String,        // 来自 @EntryPoint
    val funName: String,
    val scopeParam: String,      // PageScope 参数名（默认 "scope"）
    val stateParam: String?,     // StateHolder 参数名（如 HomeState；无状态类时为 null）
    val stateClassFields: List<StateField>,  // 状态字段（从 class 声明提取）
    val bodyStateFields: List<StateField>,   // 状态字段（函数体内 remember 提升声明）
    val body: List<Stmt>,
)

data class SourceFile(
    val path: String,
    val page: PageModel?,        // 含 @EntryPoint 的函数
    val stateClass: String?,     // 状态类名（如 HomeState）
    val stateFields: List<StateField>,
)
