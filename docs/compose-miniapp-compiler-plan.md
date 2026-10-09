# Kotlin（类 Compose DSL）→ 微信小程序原生包：编译器开发方案

> 用途：委托 AI 编码代理（Codex）实现的任务规格书。
> 目标：开发者用 Kotlin + 类 Compose 的声明式写法编写 UI 与逻辑，编译后直接产出**微信小程序原生工程**（WXML/WXSS/JS/JSON，可在微信开发者工具打开、真机运行、直接提审），全程无 web-view、无运行时桥。
> 版本：v1 · 2026-09-28

---

## 0. 前置决策：为什么不是"真 Compose"，以及方案边界（必读）

**必须让委托方（Codex）先理解并接受以下事实，否则方向必错：**

1. **Jetpack Compose 无法直接转译为小程序原生包。** Compose 是 JVM 库，渲染依赖 Skia/自有布局系统；Compose 编译器插件输出的 IR 面向 Skia 指令层，与 WXML 差三个抽象层级。不存在"把 Compose runtime 搬到小程序"的路径，JetBrains 官方也只做 Web(wasmJs) target。
2. **本方案采用"自研类 Compose DSL + 编译期源码转译"。** 写法**接近** Compose（组合函数、声明式、状态驱动），但运行时不依赖 androidx。UI 调用树在编译期被静态收集，转译成 WXML + WXSS；业务逻辑用 Kotlin 编写，经 Kotlin/JS 输出为小程序逻辑层 JS。
3. **代价要诚实告知：** 这是自研编译器工程。POC（单页面静态 UI + 一个事件）约 2–3 周；状态/列表/网络再加 2 周；到可提审的生产级约 2–3 个月（单人）。复杂度上限是"你支持的 DSL 组件越多，转译器维护越重"。
4. **非目标（明确不做）：**
   - 不支持 `androidx.compose.*` 真 Compose API 与第三方 Compose 库
   - 不支持 Compose 动画/手势/自定义绘制系统（MVP 阶段）
   - 不支持动态反射、动态类加载、平台无关的 JVM API（如 `java.*` 大部分）
   - 首版只做微信小程序；支付宝/抖音/鸿蒙小程序为后续扩展目标

---

## 1. 总体架构

```
┌─────────────────────────────────────────────────────────────┐
│ 开发者层：Kotlin 源码（UI DSL + 逻辑）                        │
│   @Composable fun HomePage(state: HomeState) { ... }        │
└──────────────────────────┬──────────────────────────────────┘
                           │ Gradle 任务触发
┌──────────────────────────▼──────────────────────────────────┐
│ 编译层（Gradle 插件 + KSP + Kotlin/JS 编译）                 │
│  A. UI 收集器：遍历 @Composable 调用树 → 中间表示(IR)        │
│  B. 逻辑编译器：commonMain Kotlin → Kotlin/JS → 小程序 JS    │
│  C. 代码生成器：IR → WXML / WXSS / JS                        │
│  D. 工程装配器：输出标准小程序工程目录                        │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│ 产物层：微信小程序原生工程（可导入微信开发者工具）            │
│  app.js / app.json / pages/*.{wxml,wxss,js,json}            │
└─────────────────────────────────────────────────────────────┘
```

**运行分层（产物侧）：**
- WXML：模板（由 UI 转译器生成）
- WXSS：样式（含 rpx 单位换算）
- 逻辑层 JS：由 Kotlin 逻辑编译而来；内含最小运行时（状态管理 `setData` 适配、事件分发、导航适配）

---

## 2. 仓库结构（Codex 需要创建的工程骨架）

```
compose-miniapp/
├── settings.gradle.kts / build.gradle.kts        # 复合构建：插件 + 运行时 + 示例
├── dsl-core/          # Kotlin DSL API（开发者 import 的类）
│   ├── api/           # @Composable 注解、@EntryPoint、@State 注解
│   ├── ui/            # Row/Column/Text/Button/Image/List/Input...
│   ├── state/         # MutableState / remember / StateHolder 简化版
│   └── style/         # TextStyle / Dp / Sp / 布局参数
├── compiler/
│   ├── ui-collector/  # KSP 处理器：收集 UI 调用树（KSP SymbolProcessor）
│   ├── ir/            # 中间表示：UiNode / LayoutNode / EventBinding / StyleNode
│   ├── codegen/       # IR → WXML / WXSS / JS 生成器（字符串模板输出）
│   └── logic/         # Kotlin/JS 编译配置封装（commonMain → 小程序 JS）
├── gradle-plugin/     # Gradle 插件：任务编排、产物装配、工程目录输出
├── runtime-miniapp/   # 小程序侧最小运行时（JS，随产物注入）
│   ├── runtime.js     # setData 适配、事件绑定、生命周期钩子
│   └── api-bridge.js  # 小程序 API 的薄封装（wx.login/requestPayment 预留）
├── sample-app/        # POC 示例工程（登录页 + 列表页 + 详情页）
└── docs/              # DSL 组件清单、转译规则表、验收清单
```

---

## 3. DSL API 设计（先行定稿，Codex 不得随意扩展）

### 3.1 页面声明

```kotlin
// 页面 = 一个 @EntryPoint 组合函数
@EntryPoint("/pages/home/home")
@Composable
fun HomePage(scope: PageScope) {
    val state = scope.rememberState { HomeState() }
    Column(padding = 16.dp, spacing = 12.dp) {
        Text("欢迎", style = TextStyle(fontSize = 20.sp, bold = true))
        Button("去登录", onClick = { scope.navigateTo("/pages/login/login") })
        List(items = state.items, key = { it.id }) { item ->
            Row(align = Alignment.Center) {
                Text(item.name)
                Text(item.price, style = TextStyle(color = Color.Red))
            }
        }
    }
}
```

### 3.2 状态（简化受控模型，不做 Diffing）

```kotlin
class HomeState {
    var items by mutableStateOf(listOf<Item>())
    fun load() { /* 网络请求后赋值，触发页面级 setData 重渲染 */ }
}
```

**约束（转译器依赖这些约束才能静态化）：**
- UI 组合函数**必须**是纯声明式：禁止分支动态生成组件名、禁止反射、禁止在 UI 函数内写循环生成不确定结构（列表用 `List`/`ForEach` 组件替代）
- 状态读写只能通过 `mutableStateOf` 包裹的字段；转译器把"状态字段 → setData key"做成映射表
- 事件回调只能是具名函数引用或 lambda 字面量（编译期可绑定）

### 3.3 内置组件清单（v1 仅这些，防止转译器失控）

| 组件 | WXML 映射 | 说明 |
|---|---|---|
| Column / Row | view 容器 + flex | spacing/padding 换算 WXSS |
| Text | text 节点 | fontSize → rpx，bold/color 映射 |
| Button | button | open-type 支持（contact/share） |
| Image | image | mode/懒加载 |
| List | block + wx:for | 每项一个模板片段 |
| Input | input | 双向绑定映射 |
| ScrollView | scroll-view | 预留 |

> 每新增一个组件 = dsl-core 加 API + codegen 加一段生成规则，**必须同步**，否则编译期报错而不是静默丢失。

---

## 4. 编译流程（实现步骤，按顺序执行）

### Step 1 — Gradle 插件骨架
- 新建 `gradle-plugin`，注册 `compose-miniapp` 插件扩展：`pages` 扫描路径、输出目录 `build/miniapp`
- 任务 `assembleMiniApp`：依赖 `compileKotlinJs` 与 `kspKotlin`，串联下述步骤

### Step 2 — KSP 处理器（UI 收集器）
- 扫描所有标注 `@EntryPoint` 的组合函数
- 用 KSP 遍历函数体内调用，构造**调用树**（组件名、参数值、lambda、子节点）
- 参数值须能在编译期求值：字面量、const、简单引用；求不出就报错并提示改写
- 输出：每页面一份 `UiTree` 序列化文件（JSON），供 codegen 读取
- 验证：用 sample-app 的最小页面跑通，打印调用树 JSON

### Step 3 — 中间表示（IR）与转译规则表
- 定义 `UiNode`（type / props / children / eventBindings / styleNode）
- 编写 **转译规则表**（这是全工程的核心文档，Codex 需维护）：每个 DSL 组件 → WXML 节点 + WXSS class 模板
- 示例：`Button("去登录", onClick = { ... })` →
  - WXML：`<button class="btn_3f2a" bindtap="onClick_1">去登录</button>`
  - WXSS：`.btn_3f2a { ... }`
  - JS：`onClick_1: () => { ... }`（事件 handler 由逻辑编译产出，此处生成引用）

### Step 4 — WXML / WXSS 生成器
- IR → WXML：布局节点 → view/text/button/image/block(wx:for/wx:if)
- IR → WXSS：样式对象 → class（类名哈希化，避免冲突）；单位换算 `1.dp → 2rpx`（按 750 设计稿约定），`sp → rpx`（用 font-size 近似，首版不做系统字体缩放适配）
- 输出每页面 `home.wxml` + `home.wxss` + `home.json`

### Step 5 — 逻辑层编译（Kotlin → 小程序 JS）
- `commonMain` 逻辑模块用 Kotlin 编写，配置 **Kotlin/JS（IR 后端）** 编译，目标 ES6
- **关键约束：屏蔽浏览器/DOM API 面** —— 用 `expect/actual` 提供 `storage`、`network`、`navigate` 三个 abstraction；小程序侧 actual 调用 `wx.*`（`wx.setStorageSync`、`wx.request`、`wx.navigateTo`）
- 编译产物（`logic.js`）作为页面逻辑层入口，与 WXML 的 bindtap 引用对齐
- 验证：sample-app 里发一个真实 `wx.request` 请求，真机可见数据渲染

### Step 6 — 状态 → setData 适配（最小运行时）
- `runtime-miniapp/runtime.js` 实现：状态字段 → `this.setData({ ... })` 的映射；页面级整体重渲染（首版不做 diff，保证正确性优先）
- 列表项更新：`wx:for` 的 `wx:key` 绑定 `key` 字段
- 性能红线：单次 setData 数据量 < 256KB（微信限制），状态变更时只序列化变更字段

### Step 7 — 工程装配
- 输出目录结构：
```
build/miniapp/
├── app.js / app.json / project.config.json / sitemap.json
└── pages/
    ├── home/  (home.wxml, home.wxss, home.js, home.json)
    ├── login/ ...
    └── detail/ ...
```
- `app.json` 的 pages 数组由 @EntryPoint 路径自动聚合
- 装配完成后**必须**用微信开发者工具导入验证（见验收）

### Step 8 — 事件与导航打通
- `bindtap`/`bindinput` → JS handler；handler 里调 `scope.navigateTo("/pages/...")` → 运行时转 `wx.navigateTo`
- 页面间传参：URL 参数 → 运行时解析 → 注入 `PageScope`（首版只支持简单字符串/数字参数）

---

## 5. 验收标准（Codex 每完成一个里程碑必须自检）

**POC（M1）通过标准：**
- [ ] `gradle :sample-app:assembleMiniApp` 一键产出完整 miniapp 目录
- [ ] 微信开发者工具导入 **零报错**（编译、预览、真机）
- [ ] 场景：登录页（输入框 + 按钮）→ 列表页（wx:for 渲染 10 条）→ 详情页（URL 传参）
- [ ] 一个真实网络请求（wx.request）取数渲染
- [ ] 按钮事件 → 状态变更 → 页面刷新（setData 路径可观测）

**生产级（M4）通过标准：**
- [ ] 构建产物可上传微信后台、体验版/提审流程走通
- [ ] 组件覆盖 v1 清单全部；转译规则表与 dsl-core 同步（有自动化校验脚本）
- [ ] 逻辑层产物体积报告（首版红线：主包 < 2MB，超出需拆分包）
- [ ] 错误报告友好：任何无法静态化的写法，编译期给出"原因 + 改写建议"

---

## 6. 里程碑与工作量预估

| 里程碑 | 内容 | 预估（单人） |
|---|---|---|
| M1 | DSL 核心 + KSP 收集器 + WXML/WXSS 生成器 + 单页 POC | 2–3 周 |
| M2 | 状态 setData 适配 + List/Input + 网络层 actual | +2 周 |
| M3 | 导航/传参 + 样式系统完善 + 多页面装配 | +2–3 周 |
| M4 | 构建/CI、分包、组件校验、多小程序平台抽象 | +1–2 月 |

---

## 7. 主要风险与决策点（委托时需明确）

1. **逻辑层 API 面**（最大风险）：Kotlin/JS 默认产物依赖 DOM/浏览器全局，小程序环境没有 `window/document`。必须用 `expect/actual` 隔离，并关掉 Kotlin/JS 对 DOM 库的依赖。**决策点：逻辑复杂度高时，是否接受"逻辑也用 Kotlin 写但编译到 JS 再经 webpack 打包"的额外工序。**
2. **转译器可维护性**：组件清单必须收窄（见 3.3）。**决策点：是否接受"DSL 组件上限 = 20 个"的硬约束。**
3. **复杂交互/动画**：首版明确不覆盖；需要时在 WXML 侧手写 fallback。**决策点：是否在 M4 引入"逃生舱"——允许在 DSL 中内联原生 WXML 片段。**
4. **真机兼容**：微信基础库版本差异（如 Skyline 渲染）。首版锁定 `lazyCodeLoading` 与基础库最低版本。
5. **不要走回头路**：任何人（包括 Codex）提议"改用 WebView 套壳 / 用 Taro 再包一层"都属于需求变更，需回到需求方确认。

---

## 8. 给 Codex 的第一轮委托内容（建议原样附上）

> 阅读本文档后，按 Step 1–4 实现 M1 的**最小可运行子集**：
> 1. 建好 `dsl-core` + `gradle-plugin` + `compiler` 骨架；
> 2. DSL 只实现 `Column / Text / Button` 三个组件 + `@EntryPoint` + `PageScope.navigateTo`；
> 3. KSP 收集器跑通一个页面，输出 UI 树 JSON；
> 4. codegen 输出 `home.wxml / home.wxss / home.js / home.json` + `app.json`；
> 5. 用微信开发者工具导入 `build/miniapp` 验证零报错，交付截图与产物目录。
> 不要实现状态系统、网络、List——那是 M2。任何无法静态化的 DSL 写法直接编译期报错。
> 提交时附：仓库结构说明 + 转译规则表初稿 + 验收清单勾选结果。

---

## 9. 附录：最小示例（输入 → 期望输出）

**输入（Kotlin）：**

```kotlin
@EntryPoint("/pages/home/home")
@Composable
fun HomePage(scope: PageScope) {
    Column(padding = 16.dp) {
        Text("Hello MiniApp", style = TextStyle(fontSize = 20.sp))
        Button("Click", onClick = { scope.navigateTo("/pages/login/login") })
    }
}
```

**期望输出 `pages/home/home.wxml`：**

```xml
<view class="col_ab12">
  <text class="txt_cd34">Hello MiniApp</text>
  <button class="btn_ef56" bindtap="onClick_1">Click</button>
</view>
```

**期望输出 `pages/home/home.js`（逻辑层，节选）：**

```js
const { runtime } = require("../../runtime/runtime.js");
Page(runtime.createPage({
  data: {},
  onClick_1() { wx.navigateTo({ url: "/pages/login/login" }); },
}));
```
