// 门店点餐页：店铺信息、商品列表、购物车汇总与结算入口。

package sample.meituan

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ProductItem(
    val id: String,
    val name: String,
    val price: String,
    val sales: String,
    val image: String,
)

@EntryPoint("/pages/store/store")
@Composable
fun StorePage() {
    var storeName by remember { mutableStateOf("门店") }
    var slogan by remember { mutableStateOf("现做现送") }
    var products by remember { mutableStateOf(listOf<ProductItem>()) }
    var cartSummary by remember { mutableStateOf("购物车为空") }
    var loading by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth().background("#ffffff").borderRadius(12.dp).padding(12.dp)) {
            Image(url = "/assets/food-burger.webp", mode = "aspectFill", modifier = Modifier.width(82.dp).height(82.dp).borderRadius(8.dp))
            Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(text = storeName, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(text = slogan, color = "#777777", fontSize = 13.sp)
                Text(text = "起送 ¥20 · 配送 ¥3 · 约 30 分钟", color = "#999999", fontSize = 12.sp)
            }
        }

        Text(text = "热销推荐", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (loading) {
            Text(text = "菜单加载中...", color = "#999999")
        }
        LazyColumn {
            items(products, key = { it.id }) { item ->
                Row(modifier = Modifier.fillMaxWidth().background("#ffffff").borderRadius(12.dp).padding(10.dp)) {
                    Image(url = item.image, mode = "aspectFill", lazyLoad = true, modifier = Modifier.width(88.dp).height(88.dp).borderRadius(8.dp))
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(text = item.name, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(text = item.sales, color = "#999999", fontSize = 12.sp)
                        Text(text = item.price, color = "#ff5000", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Button(onClick = { logic.addToCart(item.id, item.name, item.price) }, modifier = Modifier.background("#ff5000").borderRadius(8.dp)) {
                            Text(text = "加入购物车", color = "#ffffff")
                        }
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().background("#222222").borderRadius(24.dp).padding(10.dp)) {
            Text(text = cartSummary, color = "#ffffff", fontSize = 14.sp, modifier = Modifier.weight(1f))
            Button(onClick = { nav("/pages/cart/cart") }, modifier = Modifier.background("#ffcf33").borderRadius(18.dp)) {
                Text(text = "去结算", color = "#222222")
            }
        }
    }
}
