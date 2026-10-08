// sample-app 详情页

class DetailState {
    var title by mutableStateOf("详情")
    var content by mutableStateOf("这是详情内容")
}

@EntryPoint("/pages/detail/detail")
@Composable
fun DetailPage(scope: PageScope, state: DetailState) {
    ScrollView {
        Column(padding = 16.dp, spacing = 8.dp) {
            Text(state.title, style = TextStyle(fontSize = 20.sp, bold = true))
            Text(state.content)
            Button("返回", onClick = { scope.navigateBack() })
        }
    }
}
