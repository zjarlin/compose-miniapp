# compose-miniapp

Kotlin Compose 风格源码 -> 微信小程序原生工程（WXML / WXSS / JS / JSON）。

本项目把「写 Kotlin（Compose 风格）→ 编译出微信小程序原生静态产物」跑通的
最小闭环。**无 web-view、无运行时渲染引擎、无 JS bridge**：UI 在编译期静态转译为 WXML 模板，
状态与事件在编译期映射为小程序原生 `setData` 与 `bindtap`。

## 快速开始

```bash
./run.sh
# 产物：
#   out/miniapp          基础 DSL 示例
#   out/miniapp-compose  标准 Compose 写法示例
#   out/miniapp-meituan  美团风格外卖示例（首页/门店/购物车/订单/我的）
```

导入微信开发者工具：工具 → 导入项目 → 选择 `out/miniapp-meituan`。

## 支付边界

仓库内提供微信支付后端适配层（Java 17 + JDK HttpServer），服务端只在配置了商户号、
API v3 密钥和商户私钥时才请求微信 `jsapi` 下单。未配置密钥时返回明确的 mock 订单，
不会伪装成真实扣款成功。小程序端通过 `logic.js` 调用后端，商户密钥不进入包体。

详细说明见 [docs/payment.md](docs/payment.md)。

支持**两种输入写法**：
1. **标准 Compose 语法**（`sample-compose/`）：`remember { mutableStateOf(...) }`、`Modifier` 链、
   `verticalArrangement = Arrangement.spacedBy(...)`、`if (cond) { }`、`LazyColumn { items(...) }`、
   `Button(onClick = {...}) { Text(...) }`、`nav(...)`/`navBack()` 导航别名。
2. **类 Compose DSL**（`sample-app/`）：更紧凑的自定义参数（`Column(padding=, spacing=)`、
   `List(items=)`、`Button("x", onClick=)`），原有写法完全兼容。

> 规格文档：《compose-miniapp-transpiler-spec.md》（/Users/zjarlin/）
> 本项目是规格第 3–9 章的实现。**技术选型差异**：V1 用「受限 DSL 递归下降解析器」替代规格中的
> KSP（KSP 接入为 V2），其余契约（IR、错误码、转译规则、装配校验）与规格一致。

## 快速开始

```bash
./run.sh   # 一键：编译转译器 → 转译两种 sample → out/miniapp 与 out/miniapp-compose → 反例测试
```

导入微信开发者工具：工具 → 导入项目 → 选择 `out/miniapp`（旧 DSL）或 `out/miniapp-compose`（标准 Compose）。

## 标准 Compose 写法（sample-compose）

```kotlin
@EntryPoint("/pages/home/home")
@Composable
fun HomePage() {
    var items by remember { mutableStateOf(listOf<Item>()) }
    var loading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "欢迎", fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
```

编译为小程序原生产物（节选）：

```xml
<button class="btn-90b790" bindtap="h_90b790f8_click">去登录</button>
<block wx:if="{{loading}}"><text>加载中...</text></block>
<block wx:for="{{items}}" wx:key="id">
  <view class="row-b5f852"><text>{{item.name}}</text></view>
</block>
```
```css
.col-8c6d21 { width: 100%; padding: 32rpx; }
```
```js
data: { items: [], loading: false },
"h_90b790f8_click": function() { wx.navigateTo({ url: "/pages/login/login" }); },
__commit(field, value) { this.setData({ [field]: value }); },
```

## 标准 Compose 覆盖矩阵

| Compose 写法 | 覆盖情况 |
|---|---|
| `var x by remember { mutableStateOf(v) }` 状态提升 | ✅ → data + `__commit` |
| `Modifier.fillMaxWidth()/padding()/width()/height()/weight()/background()` | ✅ → WXSS |
| `verticalArrangement/horizontalArrangement = Arrangement.spacedBy(x)` | ✅ → 子节点 margin |
| `Text(text=, fontSize=, fontWeight=, color=)`；`FontWeight.Bold`/`Color.Red` | ✅ → WXSS |
| `if (cond) { }` 条件渲染 | ✅ → `wx:if` |
| `LazyColumn { items(list, key={it.id}) { item -> } }` | ✅ → `wx:for` + `wx:key` |
| `Button(onClick = {...}) { Text("x") }`（content） | ✅ → `bindtap` + label |
| `Input(value=, onValueChange={ v -> ... })` 双向绑定 | ✅ → `bindinput` + `__commit` |
| `nav("路径")` / `navBack()` | ✅ → `wx.navigateTo` / `navigateBack` |
| 任意 Kotlin 表达式（`if (x>5) "a" else "b"`、字符串模板、`list.filter{}`） | ❌ 编译期 E1001 |
| 任意函数调用 / 运行时动态 UI / `forEach` 命令式循环 | ❌ 编译期报错 |
| 动画 API、手势（`Modifier.clickable`）、`LaunchedEffect` | ❌ V2 候选 |

**边界**：路线 3 只能覆盖「编译期可完全静态化的声明子集」。依赖 Kotlin 运行时求值的写法
（任意表达式、函数组合、动态结构）无法静态转译——这是路线 3 的物理边界（Kuikly 因此走运行时指令渲染）。
未覆盖写法一律编译期报错并附改写建议，绝不静默降级。

## 工程结构

```
dsl-core/api.kt                  DSL API 定义（类型参考；开发者页面写法契约）
sample-app/src/pages/*.kt        转译器输入（旧 DSL 3 页）
sample-compose/src/pages/*.kt    转译器输入（标准 Compose 3 页）
compiler/src/
  Token.kt    tokenizer（含注释/字符串/单位后缀/负数/*）
  Ast.kt      AST 节点（Value/Stmt/PageModel/StateField；VChain/StateDecl/If）
  Parser.kt   递归下降解析器（package/import/data class 跳过、remember 状态、Modifier 链、if 语句）
  Ir.kt       IR 契约：UiTree/UiNode/Prop/StyleNode/EventHandler + FNV 哈希
  Analyzer.kt 静态分析：AST→IR、LazyColumn→List、Modifier/常量映射、状态/事件注册、错误码（核心）
  WxmlWxssGen.kt  WXML/WXSS 生成器
  JsJsonGen.kt    page.js 生成器（data/__commit/handler）
  Assembler.kt    装配 app.json/pages + 四项产物校验
  Main.kt       CLI：页面目录 → 小程序工程
tests/NegativeTest.kt             反例测试（非法 DSL → 断言错误码）
build.sh / run.sh                 构建与运行（kotlinc 工具链，无 Gradle）
```

## 已验证能力（M1 + 标准 Compose 兼容层）

| 能力 | 状态 |
|---|---|
| 旧 DSL 3 页转译 | ✔ 零报错（向后兼容） |
| 标准 Compose 3 页转译（remember/Modifier/if/LazyColumn/Button content/nav） | ✔ 零报错 |
| 状态字段 → data + setData 提交（`__commit` 最小提交） | ✔ |
| 事件 → bindtap/bindinput + wx.navigateTo/navigateBack | ✔ |
| List → wx:for + wx:key；if → wx:if | ✔ |
| dp/sp → rpx 换算；Modifier/TextStyle/常量 → WXSS | ✔ |
| 反例诊断（E1001/E1002/E1006/E1008/E1009/E1012） | ✔ 7/7 |
| 产物校验（JSON 合法 / WXML 配对 / bindtap 引用 / app.json 一致） | ✔ 独立验证通过 |

## 当前限制（V1）

- 组件白名单：Column/Row/Text/Button/Image/List/Input/ScrollView/Spacer/IfBlock + LazyColumn（→List）
- 参数只接受：字面量、状态字段引用（state.x / 裸名）、循环变量（item.x）、带单位数字、
  TextStyle/Modifier 白名单、常量（Arrangement/FontWeight/Color/Alignment）
- 逻辑层 `logic.js` 目前是占位空模块；网络/存储等 Kotlin 逻辑接入见规格 §8（expect/actual）
- 不支持：真 androidx Compose、任意表达式/函数调用、动画/手势、动态 UI、分包、多小程序平台
- 页面文件名 = 页面名（HomePage.kt → home），页面路径以 @EntryPoint 为准

## 下一步（V2 候选）

1. KSP 处理器替换手写解析器（对齐规格技术选型；表达式求值放宽 E1001）
2. Kotlin 逻辑层接入（Kotlin/JS → logic.js + expect/actual 三接口）
3. 样式去重、分包、支付宝/抖音小程序输出
