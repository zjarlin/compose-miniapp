# 路线 3：编译期静态转译器 — 技术实现规格

> **定位**：本文档是「写 Kotlin（类 Compose DSL）→ 编译出微信小程序**原生静态产物**」这条路线（与 Kuikly 的运行时指令渲染相对的"编译期静态转译"）的**实现级技术规格**。
> **用途**：作为 AI 编码代理（Codex）的实现蓝图；每个章节给出数据定义、规则或伪代码，实现者不得跳过或自行改契约。
> **配套**：《compose-miniapp-compiler-plan.md》（项目计划与委托文本）为本规格的上层文档，本文档只深化技术实现。
> 版本：v1 · 2026-09-28

---

## 0. 定位与铁律

### 0.1 目标产物

```
Kotlin 源码（类 Compose DSL + Kotlin 逻辑）
        │  gradle :app:assembleMiniApp
        ▼
微信小程序原生工程（纯静态文件，无渲染引擎、无运行时桥）
  app.js / app.json / app.wxss / project.config.json / sitemap.json
  pages/<page>/<page>.{wxml,wxss,js,json}
```

### 0.2 铁律（转译器的存在前提，违反任何一条都算实现失败）

1. **只转译可静态分析的内容**。UI 调用树必须在编译期完整可知；任何运行时才确定的结构 → 编译期报错。
2. **绝不静默降级**。遇到无法转译的写法，必须编译失败并给出「位置 + 原因 + 改写建议」，禁止丢弃节点、禁止生成近似模板。
3. **产物确定性**。相同输入必须产出逐字节相同的输出（哈希类名、稳定排序）。
4. **运行时可空**。V1 的 UI 层产物不依赖任何 JS 运行时框架，只依赖生成的 page.js 中的薄适配代码（setData 提交 + 事件绑定）。

### 0.3 与路线 2（Kuikly 指令渲染）的本质差异（实现者需理解，避免走偏）

| | 路线 2（Kuikly） | 路线 3（本文档） |
|---|---|---|
| UI 呈现 | 运行时下发渲染指令，自定义组件动态渲染 | 编译期生成静态 WXML 模板 |
| 运行时依赖 | 需要渲染运行时（Kotlin/JS bundle 常驻） | 无渲染运行时，只有薄适配层 |
| 动态化 | 支持（指令下发） | 不支持（编译期定死） |
| 产物可读性 | 模板由运行时拼装，难读 | WXML 直接可读、可审计 |
| 转译成本 | 渲染引擎维护成本 | 转译规则表维护成本 |

---

## 1. 技术选型决策（先定，不讨论）

### 1.1 为什么用 KSP，不用 Compose 编译器插件

| 候选 | 结论 | 理由 |
|---|---|---|
| androidx Compose 编译器插件 | **否决** | 输出面向 Skia 指令的 IR，与 WXML 抽象层级差距过大；强制依赖 Compose runtime 语义；即使源码叫"Compose"，也不是我们控制的 |
| Kotlin Compiler Plugin（IR 访问） | V2 演进 | 能做完整表达式求值、常量折叠；但开发门槛高、跨 Kotlin 版本 API 波动大 |
| **KSP（SymbolProcessor）** | **V1 采用** | 符号级访问足够收集「函数调用树 + 参数 + lambda」；API 稳定、跨版本兼容好、Kotlin 官方维护；配合"DSL 必须简单"的约束足够 |

### 1.2 构建管线（Gradle 插件）

- 插件 id：`com.example.compose-miniapp`（示例），扩展点：
```kotlin
composeMiniApp {
    entryPointPackage = "com.example.pages"   // @EntryPoint 扫描包
    outputDir = layout.buildDirectory.dir("miniapp")   // 产物根
    minBaseLib = "2.30.0"                     // 微信基础库最低版本
    designWidth = 750                          // 设计稿宽度，rpx 换算基准
}
```
- 任务 `assembleMiniApp`：`dependsOn(kspKotlin, compileDevelopmentExecutableKotlinJs)`，编排 §4–§9 各阶段。
- 所有阶段以**文件**为输入输出（见 §10 增量构建），禁止跨阶段内存传对象。

---

## 2. 源码契约（开发者 DSL，先定稿）

### 2.1 页面声明

```kotlin
@EntryPoint("/pages/home/home")        // 路径 = 产物 pages 目录 + app.json 注册
@Composable                             // 自定义注解，非 androidx
fun HomePage(scope: PageScope) {
    Column(padding = 16.dp, spacing = 12.dp) {
        Text("Hello", style = TextStyle(fontSize = 20.sp, bold = true))
        Button("去登录", onClick = { scope.navigateTo("/pages/login/login") })
        List(items = state.items, key = { it.id }) { item ->
            Row { Text(item.name); Text(item.price) }
        }
    }
}
```

### 2.2 状态模型（V1：页面级受控刷新，不做 diff）

```kotlin
class HomeState {
    var items by mutableStateOf(listOf<Item>())   // 集合 → wx:for 数据源
    var loading by mutableStateOf(false)
    fun load() { /* 可调逻辑层；赋值触发编译期登记的 setData 提交 */ }
}
```

### 2.3 组件白名单（V1 全量，共 9 个，禁止新增）

`Column` `Row` `Text` `Button` `Image` `List` `Input` `ScrollView` `Spacer`

> 每新增一个组件 = dsl-core 加 API + 转译规则表加一行 + 至少一个 golden test。**三者缺一，编译插件报错。**

### 2.4 禁止清单（KSP 处理器直接拒绝并报错）

- 动态组件名：`when` 分支返回不同组件、字符串拼接组件调用
- 反射（`::class`、`Class.forName`）
- UI 函数内 `while`/递归生成不确定结构
- 调用 `java.*`、`kotlinx.coroutines` 之外的平台 API
- `androidx.compose.*` 导入（防串味）

---

## 3. 中间表示 IR（核心契约，先于一切代码生成实现）

### 3.1 顶层结构

```kotlin
data class UiTree(
    val pagePath: String,                    // "/pages/home/home"
    val pageName: String,                    // "home"
    val root: UiNode,
    val stateFields: List<StateField>,       // 状态注册表（§6）
    val eventHandlers: List<EventHandler>,   // 事件注册表（§7）
    val imports: List<LogicImport>,          // 逻辑层函数引用（§8）
)

data class UiNode(
    val id: String,                          // 稳定节点 id，见 §3.2
    val type: ComponentType,                 // 白名单枚举
    val props: List<Prop>,                   // 有序，确定性输出依赖有序
    val style: StyleNode?,                   // 归一化后的样式
    val events: List<EventBinding>,          // 该节点的事件绑定
    val children: List<UiNode>,
    val loop: LoopContext?,                  // 若在 List 内，携带作用域变量与数据源
)

sealed class Prop {
    data class Literal<T>(val value: T)                  // 字符串/数字/布尔
    data class StateRef(val field: String)               // 指向 stateFields
    data class LoopVar(val name: String, val prop: Prop?)// wx:for 作用域变量（如 item.name）
    data class ComposeConst(val expr: String)            // 编译期求值后的常量表达式文本
}

data class StyleNode(
    val className: String,                   // 哈希类名（§5.2）
    val declarations: List<StyleDecl>,       // 有序：property -> value(rpx 换算后文本)
    val mediaQueries: List<StyleDecl>? = null // V1 支持 page 级 wxss 注入，暂不展开
)

data class LoopContext(
    val itemsRef: StateRef,                  // 数据源（必须是状态字段）
    val itemVar: String,                     // item 变量名
    val indexVar: String?,                   // index 变量名
    val keyField: String?,                   // wx:key 绑定的字段
)
```

### 3.2 节点 id 规则（确定性）

```
nodeId = hash("<pagePath>|<组件类型>|<父节点id>|<序号>")   // 取 8 位十六进制
```
- 序号 = 兄弟节点内从左到右的 0 基序号
- 所有 WXML class / JS handler 名 / wx:key 均派生自 nodeId，保证唯一与稳定

### 3.3 序列化

- 每个页面产出 `<page>.uitree.json`（UTF-8，字段顺序即定义顺序）
- 该文件是 codegen 的唯一输入，也是增量缓存与 golden test 的对象

---

## 4. KSP 处理器：静态分析规则与诊断

### 4.1 处理流程（SymbolProcessor）

```
process():
  1. 收集 @EntryPoint 注解的函数 → 候选页面列表（校验路径格式 /pages/xx/xx）
  2. 对每个页面函数：
     a. 遍历函数体调用表达式 → 构建调用树（§4.2）
     b. 参数求值（§4.3），失败则记录诊断并终止该页面
     c. 收集事件 lambda → 事件注册表
     d. 收集 scope/StateHolder 的 mutableStateOf 属性 → 状态注册表
     e. 序列化 UiTree → <page>.uitree.json
  3. 全部页面成功 → 输出页面清单 manifest.json；任一失败 → 抛错终止构建
```

### 4.2 调用树构建

- 只识别**白名单组件函数**（包名 + 函数名双重校验）
- 组合函数可以嵌套调用其他组合函数（如 `fun CommonHeader() = Column {...}`）——V1 支持**单层**函数复用：对非白名单组合函数做**内联展开**（把其体内调用树并入调用点），深度超过 3 层则报错 E1005
- lambda 分类：
  - 事件 lambda（参数是 `() -> Unit` 或 `(T) -> Unit`，绑定到 events）
  - List 的 items lambda（参数即 item 变量 → LoopContext）
  - 其他 lambda → 报错 E1006

### 4.3 参数求值规则（优先级递减）

| 优先级 | 形态 | 结果 |
|---|---|---|
| 1 | 字面量 / `const val` | `Literal` |
| 2 | 简单属性引用（`val x = "abc"`，无计算） | `Literal`（折叠为值） |
| 3 | 状态字段（`state.xxx` 且 xxx 已注册） | `StateRef` |
| 4 | `List` 作用域内 `item.xxx` | `LoopVar` |
| 5 | 编译期可求值的纯表达式（常量算术/字符串拼接） | `ComposeConst` |
| 6 | 其他 | **报错 E1001** |

> 注意：`Text(state.userName)` 合法（StateRef）；`Text(state.getDisplayName())` **不合法**（方法调用 → E1001），改写建议：把计算挪到逻辑层，结果存进状态字段。

### 4.4 诊断系统（错误码表，见 §12）

- 每个错误输出：`文件:行:列  E10xx 原因 + 改写建议示例`
- KSP 阶段任一错误 → 整个构建失败，不产出半成品目录

---

## 5. 组件转译规则表（代码生成器唯一事实来源）

### 5.1 单位换算（WXSS 输出前统一执行）

| DSL 单位 | WXSS 输出 | 规则 |
|---|---|---|
| `1.dp` | `2rpx` | `rpx = dp * (750 / designWidth)`，designWidth=750 时 1dp=2rpx |
| `1.sp` | `2rpx` | 首版按 1sp=2rpx 近似，不做系统字体缩放适配 |
| 百分比 | 百分比 | 原样透传 |
| 颜色 | `#RRGGBB` | 统一转 6 位十六进制 |
| `Padding(16.dp, 8.dp, 16.dp, 8.dp)` | `padding: 32rpx 16rpx;` | 四参缩写展开，按 WXSS 语法 |

### 5.2 类名生成

```
class = "<前缀>-<nodeId前6位>"
前缀：col- / row- / txt- / btn- / img- / list- / input- / scroll- / spacer-
```
- 相同样式的节点**不合并** class（V1 求确定性优先于产物体积；V2 可做样式去重）

### 5.3 全量映射表

| DSL | WXML 模板（伪代码） | 事件/状态绑定 |
|---|---|---|
| `Column` | `<view class="col-xxx">…children…</view>` | — |
| `Row` | `<view class="row-xxx">…children…</view>` | — |
| `Text` | `<text class="txt-xxx">{%Text内容%}</text>` | 内容可为 Literal / StateRef / LoopVar |
| `Button` | `<button class="btn-xxx"{%bindtap%}>{%label%}</button>` | onClick → `bindtap="h_<nodeId>"` |
| `Image` | `<image class="img-xxx" src="{%url%}" mode="aspectFill"{%懒加载%}/>` | url 可为 StateRef；`lazyLoad=true` 时加 `lazy-load` |
| `List` | `<block wx:for="{{{%itemsRef%}}}" wx:key="{%keyField%}">{%item模板%}</block>` | 数据源必须 StateRef；item 模板由 children 在 LoopContext 下生成 |
| `Input` | `<input class="input-xxx" value="{{{%valueRef%}}}"{%bindinput%}/>` | value → StateRef；bindinput → `h_<nodeId>_input`，handler 内提交状态 |
| `ScrollView` | `<scroll-view class="scroll-xxx" scroll-y>…children…</scroll-view>` | — |
| `Spacer` | `<view class="spacer-xxx"/>` | 仅样式（宽/高） |

### 5.4 条件渲染（V1 最小子集）

- 仅支持通过 `List` 的 `isEmpty` 分支或 DSL 显式 `ifState(field) { … }` 组件生成 `wx:if="{{!field}}"` / `wx:else`
- **禁止**任意表达式条件（`if (a && b)`）→ E1007
- 白名单组件 `IfBlock(condition: StateRef, then: @Composable () -> Unit)`（V1 新增第 10 个组件，需同步规则表）

### 5.5 生成器实现约定

- 三个生成器：`WxmlGenerator` / `WxssGenerator` / `JsGenerator`，**各自独立、以 UiTree 为输入**
- 输出全部为字符串拼接（禁用模板引擎，保证确定性）；缩进固定 2 空格
- WXML 属性顺序固定：`class → 数据属性 → 事件属性`（便于 diff 与 golden test）

---

## 6. 状态 → setData 机制

### 6.1 状态注册表

```kotlin
data class StateField(
    val field: String,        // "items" / "loading"
    val dataKey: String,      // data 中的 key（= field，V1 一致）
    val kind: StateKind,      // VALUE / LIST（列表需特殊处理 wx:key）
)
```

### 6.2 生成的 page.js 结构（模板）

```js
const { logic } = require("../../logic/logic.js");

Page({
  data: {
    items: [],          // ← 每个 StateField 一个 key，初始值来自编译期可求值默认值
    loading: false,
  },
  onLoad() {
    // 页面生命周期 → 调逻辑层初始化（若页面声明了 init 逻辑）
  },
  "h_<nodeId>": function (e) {
    // 事件处理器：调用逻辑层或直接操作
    this.__commit("loading", true);
    logic.fetchItems().then((items) => this.__commit("items", items));
  },
  __commit(field, value) {
    const patch = {};
    patch[field] = value;
    this.setData(patch);   // 只提交变更字段，满足 <256KB 红线
  },
});
```

### 6.3 WXML 侧引用

- `StateRef` → `{{field}}`
- `LoopVar` → `{{item.name}}`
- 集合状态 → `wx:for="{{items}}" wx:key="id"`

### 6.4 规则

- 状态字段的**写入点**只能在：事件 handler、逻辑层回调（通过 handler 传入的 `commit` 函数）。KSP 检测到 UI 组合函数内直接改状态 → E1008
- `setData` 每次只提交变更字段；V1 不做 diff，页面级状态变更 = 该字段全量重提交

---

## 7. 事件系统转译

### 7.1 事件注册表

```kotlin
data class EventHandler(
    val handlerId: String,        // "h_<nodeId>" 或 "h_<nodeId>_input"
    val pagePath: String,
    val kind: EventKind,          // NAVIGATE / COMMIT / LOGIC_CALL / COMBINED
    val body: HandlerBody,        // 转译后的 JS 语句列表（有序）
)
```

### 7.2 lambda 转译规则（V1）

| lambda 内写法 | 转译 | kind |
|---|---|---|
| `scope.navigateTo("/p/x")` | `wx.navigateTo({ url: "/p/x" })` | NAVIGATE |
| `scope.navigateBack()` | `wx.navigateBack()` | NAVIGATE |
| `state.field = value` | `this.__commit("field", value)` | COMMIT |
| `logic.fn(args)` | `logic.fn(args).then(r => this.__commit(...))`（若返回 Promise） | LOGIC_CALL |
| 以上组合 | 按序拼接 | COMBINED |
| 其他（循环、条件、闭包捕获等） | **报错 E1009**，改写建议：把逻辑移到逻辑层函数 | — |

### 7.3 Input 双向绑定

- `bindinput` → `h_<nodeId>_input`，固定转译：`this.__commit("<valueRef字段>", e.detail.value)`

---

## 8. 逻辑层：Kotlin/JS 接入

### 8.1 分工

| 层 | 语言 | 产物 |
|---|---|---|
| UI（WXML/WXSS） | DSL（Kotlin 编译期消耗） | 静态模板 |
| 页面适配（page.js） | 生成器产出 | 事件绑定 + setData 提交 |
| 业务逻辑（网络/存储/计算） | Kotlin（commonMain） | `logic.js`（Kotlin/JS 编译） |

### 8.2 Kotlin/JS 配置（逻辑模块）

```kotlin
kotlin {
    js(IR) {
        browser {
            webpackTask { outputFileName = "logic.js" }
            webpackConfigApplier { /* config.target = 'node'：关 DOM 依赖 */ }
        }
        binaries.executable()
    }
}
```

### 8.3 API 面隔离（expect/actual，唯一允许触碰 wx.* 的入口）

```kotlin
// commonMain
expect class Storage { fun get(key: String): String?; fun set(key: String, v: String) }
expect fun request(url: String, method: String, body: String?): Promise<String>
expect fun navigateTo(path: String)

// jsMain (actual) — 只能写 wx.* 调用
actual class Storage {
    actual fun get(key: String) = wx.setStorageSync(key) as String?   // 经 @JsModule/声明桥接
    ...
}
```

- **禁止**在 commonMain 直接引用 `wx`、`window`、`document`（KSP 按 import 检查）
- 逻辑层导出函数用 `@JsExport` 标注，生成器从 UiTree 的 `imports` 生成 `require` 与调用

### 8.4 产物体积红线

- `logic.js` + 页面产物合计：主包 < 2MB（微信主包限制）；超出 → 构建报错，提示拆分分包（V2 支持分包配置）

---

## 9. 工程装配器

### 9.1 输出目录（唯一权威结构）

```
build/miniapp/
├── app.js                     // App({}) 空壳 + 全局逻辑初始化
├── app.json                   // pages 数组（@EntryPoint 顺序）、window 配置
├── app.wxss                   // 全局样式（page 级 reset，含 rpx 基准注释）
├── project.config.json        // appid 占位、编译配置、miniprogramRoot 指向本目录
├── sitemap.json
├── logic/logic.js             // Kotlin/JS 产物（§8）
└── pages/
    ├── home/  (home.wxml, home.wxss, home.js, home.json)
    ├── login/ ...
    └── detail/ ...
```

### 9.2 装配规则

- `app.json.pages` = 全部 @EntryPoint 路径，按源码包路径字典序排序（确定性）
- 页面 `home.json`：`{ "usingComponents": {}, "navigationBarTitleText": <页面标题参数> }`
- 静态资源（`commonMain/assets`）整体复制到 `dist/assets`
- **校验器**（产物交付前强制跑）：检查 ① 每个 wxml 的 bindtap/bindinput 在对应 js 中存在 handler；② 所有 json 可 parse；③ `{{}}` 引用的 data key 均已注册；④ wx:for 的 items 是已注册 LIST 状态。任一失败 → 构建失败并列出清单

---

## 10. 增量构建与缓存

- 缓存键：`<page>.uitree.json` 的哈希
- Gradle `@Input/@Output` 全部落到文件：输入 = 源码文件集合 + 规则表版本号；输出 = uitree.json + 生成产物
- 规则表（§5）升级 = 版本号 +1 → 全量重生成（保证确定性不被破坏）

---

## 11. 测试策略

| 类型 | 内容 | 必须覆盖 |
|---|---|---|
| Golden Test | DSL 输入 → 期望 WXML/WXSS/JS 快照 | 每个组件至少 1 例；List 嵌套 1 例 |
| 契约测试 | 规则表每行一个用例（§5.3 全量） | 9+1 组件全量 |
| 反例测试 | 非法 DSL → 断言错误码 | E1001/E1002/E1005-E1009 各至少 1 例 |
| 端到端 | `miniprogram-ci` 上传/预览 | CI 必跑：构建 → 导入 → 编译零报错 |
| 产物校验 | §9.2 校验器 | 每个构建必跑 |

---

## 12. 错误码表（诊断契约）

| 码 | 触发 | 改写建议（必须随错误输出） |
|---|---|---|
| E1001 | 参数无法静态求值（方法调用/复杂表达式） | 把计算移到逻辑层，结果存入状态字段后引用 |
| E1002 | 动态组件名/结构 | 改用白名单组件 + IfBlock 显式分支 |
| E1003 | 使用禁止 API（java.* / DOM / wx 直调） | 通过 expect/actual 封装到逻辑层 |
| E1005 | 组合函数内联深度 > 3 | 拆页面或减少嵌套组合 |
| E1006 | 不支持位置的 lambda | 移入事件参数或 List items 参数 |
| E1007 | 任意表达式条件渲染 | 改用 IfBlock(StateRef) |
| E1008 | UI 组合函数内直接改状态 | 改到事件 handler / 逻辑层 |
| E1009 | 事件 lambda 内无法转译的控制流 | 把逻辑封装到逻辑层函数 |
| E1010 | 主包超 2MB | 拆分逻辑分包（V2） |

---

## 13. 边界与 V2 演进

**V1 明确不做**：动画/手势、自定义绘制、任意表达式、动态路由表、分包、多小程序平台、样式去重合并。

**V2 候选（按优先级）**：
1. Kotlin Compiler Plugin 替换 KSP（支持表达式求值，放宽 E1001）
2. 样式去重合并（产物瘦身）
3. 分包配置 + 按页拆 logic
4. 支付宝 / 抖音 / 鸿蒙小程序输出（新增 RenderTarget 抽象，规则表参数化）

---

## 14. 验收清单（M1 通过标准，逐项勾选）

- [ ] `gradle :sample:assembleMiniApp` 一键产出 `build/miniapp`，结构符合 §9.1
- [ ] 微信开发者工具导入零报错；真机预览 3 页可交互（登录→列表→详情）
- [ ] `List` 渲染 10 条数据；按钮事件 → 状态变更 → `setData` 可观测（调试器 Network/AppData）
- [ ] 一个 `wx.request` 网络请求取数渲染（走 §8 expect/actual）
- [ ] 反例测试全绿：5 类非法 DSL 均编译期报错并附改写建议
- [ ] golden test 快照提交入库；规则表版本号机制生效

---

## 15. 给 Codex 的 M1 实现规格（可直接粘贴）

> 实现路线 3 编译器（规格见上方全部章节），**只做 M1 最小闭环**：
> 1. `dsl-core`：`@EntryPoint`/`@Composable` 注解、`PageScope`（navigateTo/navigateBack）、组件 `Column/Row/Text/Button`、`mutableStateOf`（仅做标记，不做运行时）、`Dp/Sp/TextStyle` 类型；
> 2. `compiler`（KSP）：按 §4 收集调用树、按 §4.3 求值、输出 `UiTree` JSON（§3.3）；
> 3. `codegen`：按 §5 映射表实现 WXML/WXSS 生成器，按 §6/§7 生成 page.js 与事件绑定；
> 4. `gradle-plugin`：`assembleMiniApp` 装配 `app.json/pages/project.config.json`（§9）+ 校验器；
> 5. 反例诊断：实现 E1001/E1002/E1006/E1008 四类错误；
> 6. 交付：仓库 + golden test（每组件 1 例）+ 微信开发者工具导入零报错截图 + 验收清单勾选结果。
> 禁止：实现运行时渲染引擎、状态 diff、分包、多平台——那是 V2。任何超出白名单组件的 DSL 一律编译期报错。
