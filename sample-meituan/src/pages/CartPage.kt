// 购物车页：数量调整、优惠券、配送地址、创建订单并发起微信支付。

package sample.meituan

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class CartItem(
    val id: String,
    val name: String,
    val price: String,
    val quantity: String,
    val subtotal: String,
    val image: String,
)

@EntryPoint("/pages/cart/cart")
@Composable
fun CartPage() {
    var items by remember { mutableStateOf(listOf<CartItem>()) }
    var total by remember { mutableStateOf("¥0.00") }
    var empty by remember { mutableStateOf(true) }
    var address by remember { mutableStateOf("上海市浦东新区世纪大道 100 号") }
    var coupon by remember { mutableStateOf("暂无可用优惠券") }
    var paying by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = "购物车", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.fillMaxWidth().background("#ffffff").borderRadius(12.dp).padding(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "配送地址", color = "#888888", fontSize = 12.sp)
                Text(text = address, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Button(onClick = { logic.chooseAddress() }, modifier = Modifier.background("#fff3eb").borderRadius(8.dp)) {
                Text(text = "修改", color = "#ff5000")
            }
        }

        if (empty) {
            Column(modifier = Modifier.fillMaxWidth().background("#ffffff").borderRadius(12.dp).padding(24.dp)) {
                Text(text = "购物车还是空的", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(text = "去附近商家挑点好吃的", color = "#999999", fontSize = 13.sp)
                Button(onClick = { nav("/pages/home/home") }, modifier = Modifier.fillMaxWidth().background("#ff5000").borderRadius(8.dp)) {
                    Text(text = "去逛逛", color = "#ffffff")
                }
            }
        }

        LazyColumn {
            items(items, key = { it.id }) { item ->
                Row(modifier = Modifier.fillMaxWidth().background("#ffffff").borderRadius(12.dp).padding(10.dp)) {
                    Image(url = item.image, mode = "aspectFill", modifier = Modifier.width(70.dp).height(70.dp).borderRadius(8.dp))
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(text = item.name, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(text = item.price, color = "#ff5000", fontSize = 14.sp)
                        Text(text = item.subtotal, color = "#777777", fontSize = 12.sp)
                    }
                    Column(modifier = Modifier.width(110.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Button(onClick = { logic.adjustQuantity(item.id, -1) }, modifier = Modifier.background("#f3f3f3").borderRadius(14.dp)) {
                                Text(text = "-")
                            }
                            Text(text = item.quantity, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Button(onClick = { logic.adjustQuantity(item.id, 1) }, modifier = Modifier.background("#ff5000").borderRadius(14.dp)) {
                                Text(text = "+", color = "#ffffff")
                            }
                        }
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().background("#ffffff").borderRadius(12.dp).padding(12.dp)) {
            Text(text = "优惠券", color = "#666666", fontSize = 14.sp, modifier = Modifier.weight(1f))
            Button(onClick = { logic.selectCoupon("new-user") }, modifier = Modifier.background("#fff3eb").borderRadius(8.dp)) {
                Text(text = coupon, color = "#ff5000")
            }
        }

        Row(modifier = Modifier.fillMaxWidth().background("#222222").borderRadius(24.dp).padding(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "合计", color = "#bbbbbb", fontSize = 12.sp)
                Text(text = total, color = "#ffffff", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Button(onClick = { logic.checkout() }, modifier = Modifier.background("#ffcf33").borderRadius(20.dp)) {
                Text(text = "微信支付", color = "#222222", fontWeight = FontWeight.Bold)
            }
        }
        if (paying) {
            Text(text = "正在创建订单并唤起微信支付...", color = "#ff5000", fontSize = 13.sp)
        }
        Button(onClick = { logic.clearCart() }, modifier = Modifier.fillMaxWidth().background("#ffffff").borderRadius(8.dp)) {
            Text(text = "清空购物车", color = "#999999")
        }
    }
}
