// sample-compose：登录页（标准 Compose 写法）
// 覆盖：remember 状态、Input onValueChange、Button content、navBack

package sample.compose

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@EntryPoint("/pages/login/login")
@Composable
fun LoginPage() {
    var input by remember { mutableStateOf("") }
    var agreed by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = "登录", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Input(
            value = input,
            placeholder = "请输入手机号",
            onValueChange = { v -> input = v }
        )
        Button(onClick = { navBack() }) {
            Text(text = "提交")
        }
    }
}
