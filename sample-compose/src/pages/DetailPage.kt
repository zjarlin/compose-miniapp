// sample-compose：详情页（标准 Compose 写法）
// 覆盖：ScrollView、remember 状态裸名引用、Button content、navBack

package sample.compose

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@EntryPoint("/pages/detail/detail")
@Composable
fun DetailPage() {
    var title by remember { mutableStateOf("商品详情") }
    var content by remember { mutableStateOf("这里是商品详情内容") }

    ScrollView {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(text = content)
            Button(onClick = { navBack() }) {
                Text(text = "返回")
            }
        }
    }
}
