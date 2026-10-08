// 我的页：用户、优惠券、订单入口和支付环境说明。

package sample.meituan

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@EntryPoint("/pages/mine/mine")
@Composable
fun MinePage() {
    var nickname by remember { mutableStateOf("微信用户") }
    var phone by remember { mutableStateOf("") }
    var loginText by remember { mutableStateOf("登录") }
    var couponText by remember { mutableStateOf("新人券 2 张") }
    var payMode by remember { mutableStateOf("支付模式：等待后端响应") }

    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth().background("#fff3eb").borderRadius(14.dp).padding(16.dp)) {
            Image(url = "/assets/avatar.webp", mode = "aspectFill", modifier = Modifier.width(64.dp).height(64.dp).borderRadius(32.dp))
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(text = nickname, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(text = "会员等级：黄金会员", color = "#8a5a44", fontSize = 13.sp)
            }
        }

        Row(modifier = Modifier.fillMaxWidth().background("#ffffff").borderRadius(12.dp).padding(12.dp)) {
            Text(text = couponText, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = { logic.useCoupon("new-user") }, modifier = Modifier.background("#fff3eb").borderRadius(8.dp)) {
                Text(text = "去使用", color = "#ff5000")
            }
        }

        Button(onClick = { nav("/pages/orders/orders") }, modifier = Modifier.fillMaxWidth().background("#ffffff").borderRadius(10.dp)) {
            Text(text = "我的订单")
        }
        Button(onClick = { nav("/pages/cart/cart") }, modifier = Modifier.fillMaxWidth().background("#ffffff").borderRadius(10.dp)) {
            Text(text = "购物车")
        }

        Column(modifier = Modifier.fillMaxWidth().background("#ffffff").borderRadius(12.dp).padding(12.dp)) {
            Text(text = "手机号登录", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Input(value = phone, placeholder = "请输入手机号", onValueChange = { v -> phone = v }, modifier = Modifier.fillMaxWidth().background("#f7f7f7").borderRadius(8.dp))
            Button(onClick = { logic.login(phone) }, modifier = Modifier.fillMaxWidth().background("#ff5000").borderRadius(8.dp)) {
                Text(text = loginText, color = "#ffffff")
            }
        }

        Column(modifier = Modifier.fillMaxWidth().background("#ffffff").borderRadius(12.dp).padding(12.dp)) {
            Text(text = "支付环境", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(text = payMode, color = "#666666", fontSize = 13.sp)
            Text(text = "生产支付需配置商户号、API v3 密钥、商户私钥和平台证书。", color = "#999999", fontSize = 12.sp)
        }
    }
}
