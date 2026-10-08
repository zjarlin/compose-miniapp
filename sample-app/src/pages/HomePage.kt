// sample-app：转译器输入页面（由编译器静态解析，不参与 Kotlin 编译）
// 若需 IDE 提示，可把 dsl-core/api.kt 加入同一工程。

class HomeState {
    var items by mutableStateOf(listOf<Item>())
    var loading by mutableStateOf(false)
}

@EntryPoint("/pages/home/home")
@Composable
fun HomePage(scope: PageScope, state: HomeState) {
    Column(padding = 16.dp, spacing = 12.dp) {
        Text("欢迎使用 Compose MiniApp", style = TextStyle(fontSize = 20.sp, bold = true))
        Button("去登录", onClick = { scope.navigateTo("/pages/login/login") })
        IfBlock(state.loading) {
            Text("加载中...")
        }
        List(items = state.items, key = { it.id }) { item ->
            Row(padding = 8.dp) {
                Text(item.name)
                Text(item.price, style = TextStyle(color = "#ff5000"))
            }
        }
    }
}
