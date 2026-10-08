// sample-compose：标准 Compose 写法页面（由转译器静态转译，不参与 Kotlin 编译）
// 覆盖：remember 状态提升、Modifier 链、verticalArrangement、if 语句、
//       LazyColumn+items、Button content、nav 导航别名

package sample.compose

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color

data class Item(val id: Int, val name: String, val price: String)

@EntryPoint("/pages/home/home")
@Composable
fun HomePage() {
    var items by remember { mutableStateOf(listOf<Item>()) }
    var loading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "欢迎使用 Compose MiniApp", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Button(onClick = { nav("/pages/login/login") }) {
            Text(text = "去登录")
        }
        if (loading) {
            Text(text = "加载中...")
        }
        LazyColumn {
            items(items, key = { it.id }) { item ->
                Row(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(text = item.name)
                    Text(text = item.price, color = Color.Red)
                }
            }
        }
    }
}
