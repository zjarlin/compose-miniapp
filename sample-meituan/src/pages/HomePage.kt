// 美团风格首页：定位、搜索、品类入口、活动卡片、附近门店。
// 只使用转译器支持的标准 Compose 子集；数据由 logic.js 注入。

package sample.meituan

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class StoreItem(
    val id: String,
    val name: String,
    val category: String,
    val distance: String,
    val rating: String,
    val delivery: String,
    val cover: String,
)

@EntryPoint("/pages/home/home")
@Composable
fun HomePage() {
    var city by remember { mutableStateOf("上海") }
    var address by remember { mutableStateOf("选择收货地址") }
    var keyword by remember { mutableStateOf("") }
    var stores by remember { mutableStateOf(listOf<StoreItem>()) }
    var loading by remember { mutableStateOf(false) }

    ScrollView(scrollY = true) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(text = city, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(text = address, color = "#666666", fontSize = 13.sp)
                Button(onClick = { logic.chooseAddress() }, modifier = Modifier.background("#ffffff")) {
                    Text(text = "切换")
                }
            }

            Row(modifier = Modifier.fillMaxWidth().background("#ffffff").borderRadius(12.dp).padding(8.dp)) {
                Text(text = "搜索商家、商品", color = "#999999", fontSize = 14.sp)
                Button(onClick = { logic.refresh() }, modifier = Modifier.background("#ff5000").borderRadius(16.dp)) {
                    Text(text = "搜索", color = "#ffffff")
                }
            }

            Row(modifier = Modifier.fillMaxWidth().background("#fff3eb").borderRadius(12.dp).padding(12.dp)) {
                Image(url = "/assets/food-hotpot.webp", mode = "aspectFill", modifier = Modifier.width(86.dp).height(70.dp).borderRadius(8.dp))
                Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(text = "今日外卖神券", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = "#ff5000")
                    Text(text = "满 30 减 12，最高再减 20", color = "#7a4b33", fontSize = 13.sp)
                }
            }

            Text(text = "美食分类", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "美食", color = "#ff5000")
                Text(text = "甜点饮品", color = "#555555")
                Text(text = "超市便利", color = "#555555")
                Text(text = "买药", color = "#555555")
            }

            Text(text = "附近商家", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (loading) {
                Text(text = "正在加载附近商家...", color = "#999999")
            }
            LazyColumn {
                items(stores, key = { it.id }) { item ->
                    Column(modifier = Modifier.fillMaxWidth().background("#ffffff").borderRadius(12.dp).padding(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Image(url = item.cover, mode = "aspectFill", lazyLoad = true, modifier = Modifier.width(92.dp).height(78.dp).borderRadius(8.dp))
                            Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(text = item.name, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Text(text = item.category, color = "#888888", fontSize = 12.sp)
                                Text(text = item.rating, color = "#ff8a00", fontSize = 13.sp)
                                Text(text = item.delivery, color = "#666666", fontSize = 12.sp)
                            }
                            Text(text = item.distance, color = "#999999", fontSize = 12.sp)
                        }
                        Button(onClick = { logic.openStore(item.id) }, modifier = Modifier.fillMaxWidth().background("#ff5000").borderRadius(8.dp)) {
                            Text(text = "去点餐", color = "#ffffff")
                        }
                    }
                }
            }
        }
    }
}
