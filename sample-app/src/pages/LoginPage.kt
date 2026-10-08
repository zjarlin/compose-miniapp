// sample-app 登录页

class LoginState {
    var input by mutableStateOf("")
    var agreed by mutableStateOf(false)
}

@EntryPoint("/pages/login/login")
@Composable
fun LoginPage(scope: PageScope, state: LoginState) {
    Column(padding = 24.dp, spacing = 16.dp) {
        Text("登录", style = TextStyle(fontSize = 24.sp, bold = true))
        Input(value = state.input, onInput = { v -> state.input = v }, placeholder = "请输入手机号")
        Button("提交", onClick = { scope.navigateBack() })
    }
}
