// 订单页：展示订单状态、金额和微信支付入口。

package sample.meituan

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class OrderItem(
    val id: String,
    val title: String,
    val amount: String,
    val status: String,
    val createdAt: String,
)

@EntryPoint("/pages/orders/orders")
@Composable
fun OrdersPage() {
    var orders by remember { mutableStateOf(listOf<OrderItem>()) }
    var empty by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "我的订单", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Button(onClick = { logic.refresh() }, modifier = Modifier.background("#fff3eb").borderRadius(8.dp)) {
                Text(text = "刷新", color = "#ff5000")
            }
        }
        if (empty) {
            Column(modifier = Modifier.fillMaxWidth().background("#ffffff").borderRadius(12.dp).padding(24.dp)) {
                Text(text = "暂无订单", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(text = "下单后可在这里查看配送和支付状态", color = "#999999", fontSize = 13.sp)
            }
        }
        LazyColumn {
            items(orders, key = { it.id }) { item ->
                Column(modifier = Modifier.fillMaxWidth().background("#ffffff").borderRadius(12.dp).padding(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = item.title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(text = item.status, color = "#ff5000", fontSize = 13.sp)
                    }
                    Text(text = item.createdAt, color = "#999999", fontSize = 12.sp)
                    Text(text = item.amount, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Button(onClick = { logic.payOrder(item.id) }, modifier = Modifier.fillMaxWidth().background("#ff5000").borderRadius(8.dp)) {
                        Text(text = "微信支付/查看", color = "#ffffff")
                    }
                }
            }
        }
    }
}
