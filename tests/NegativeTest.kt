package compose.miniapp.compiler

// 反例测试：非法 DSL 必须在编译期报指定错误码（规格 §4.4 / §12）

fun main() {
    var passed = 0
    var failed = 0

    fun expectError(code: String, name: String, src: String) {
        try {
            val f = Parser(src, "$name.kt").parseFile()
            Analyzer.analyze(f)
            println("✗ $name：期望错误 $code，但编译通过了")
            failed++
        } catch (e: CompileException) {
            if (e.code == code) {
                println("✔ $name -> ${e.code}")
                passed++
            } else {
                println("✗ $name：期望 $code，实际 ${e.code}（${e.message}）")
                failed++
            }
        } catch (e: ParseException) {
            println("✗ $name：解析错误而非 $code（${e.message}）")
            failed++
        } catch (e: Exception) {
            println("✗ $name：意外异常 ${e.javaClass.simpleName}（${e.message}）")
            failed++
        }
    }

    fun page(body: String): String = """
        class S { var items by mutableStateOf(listOf<X>()) }
        @EntryPoint("/pages/t/t")
        @Composable
        fun T(scope: PageScope, state: S) {
            $body
        }
    """.trimIndent()

    // E1002：未知组件
    expectError("E1002", "unknown_component", page("Foo(\"x\")"))
    // E1001：参数无法静态求值（方法调用）
    expectError("E1001", "method_call_arg", page("""Text(state.getTitle())"""))
    // E1008：UI 组合函数内直接改状态
    expectError("E1008", "direct_state_write", page("""state.loading = true"""))
    // E1009：事件 lambda 内调用状态方法
    expectError("E1009", "event_state_method", page("""Button("x", onClick = { state.load() })"""))
    // E1006：不接受块的组件带块参数
    expectError("E1006", "text_with_block", page("""Text("x") { Column {} }"""))
    // E1012：顶层不是根组件
    expectError("E1012", "top_level_non_component", page("""scope.navigateTo("/pages/x/x")"""))
    // E1001：List 引用未注册状态字段
    expectError("E1001", "list_unregistered_state", page("""List(items = state.unknown, key = { it.id }) { item -> Text(item.a) }"""))

    println()
    println("反例测试：通过 $passed / 失败 $failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
