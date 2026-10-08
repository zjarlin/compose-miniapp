package compose.miniapp.compiler

// ---------- IR（对应规格 §3） ----------

data class UiTree(
    val pagePath: String,
    val pageName: String,
    val root: UiNode,
    val stateFields: List<StateField>,
    val eventHandlers: List<EventHandler>,
)

data class UiNode(
    val id: String,
    val type: ComponentType,
    val props: List<Prop>,
    val style: StyleNode?,
    val events: List<EventBinding>,
    val children: List<UiNode>,
    val loop: LoopContext?,
) {
    val eventBindings: Map<String, String> get() = events.associate { it.eventAttr to it.handlerId }
}

enum class ComponentType(val wxmlTag: String, val classPrefix: String) {
    Column("view", "col-"),
    Row("view", "row-"),
    Text("text", "txt-"),
    Button("button", "btn-"),
    Image("image", "img-"),
    List("block", "list-"),
    Input("input", "input-"),
    ScrollView("scroll-view", "scroll-"),
    Spacer("view", "spacer-"),
    IfBlock("block", "ifb-"),
}

sealed class Prop {
    data class Literal(val text: String) : Prop()
    data class Num(val v: Double) : Prop()
    data class Bool(val v: Boolean) : Prop()
    data class StateRef(val field: String) : Prop()
    data class LoopVar(val varName: String, val field: String) : Prop()
}

data class StyleNode(
    val className: String,
    val declarations: List<Pair<String, String>>,   // property -> value（rpx 已换算）
)

enum class EventKind { NAVIGATE, COMMIT, LOGIC_CALL, COMBINED, INPUT_COMMIT }

data class EventBinding(val eventAttr: String, val handlerId: String)

data class EventHandler(
    val handlerId: String,
    val kind: EventKind,
    val jsLines: List<String>,
)

data class LoopContext(
    val itemsField: String,
    val itemVar: String,
    val keyField: String?,
)

// ---------- 确定性哈希（nodeId / className） ----------

fun stableHash(input: String): String {
    // FNV-1a 32bit → 8 位 hex
    var h = 0x811c9dc5.toInt()
    for (c in input) {
        h = h xor c.code
        h = h * 0x01000193
    }
    return "%08x".format(h.toInt()).take(8)
}
