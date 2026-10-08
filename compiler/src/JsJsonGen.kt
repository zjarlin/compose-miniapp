package compose.miniapp.compiler

// ---------- JS / JSON 生成器（规格 §6/§7） ----------

object JsGenerator {

    fun generate(tree: UiTree): String {
        val sb = StringBuilder()
        val needLogic = tree.eventHandlers.any { h -> h.jsLines.any { it.contains("logic.") } }
        if (needLogic) {
            sb.append("const { logic } = require(\"../../logic/logic.js\");\n\n")
        }
        sb.append("Page({\n")
        sb.append("  data: {\n")
        tree.stateFields.forEachIndexed { i, f ->
            sb.append("    ${f.name}: ${f.defaultRaw}")
            sb.append(if (i == tree.stateFields.size - 1) "\n" else ",\n")
        }
        sb.append("  },\n")
        sb.append("  onLoad() { if (typeof logic !== \"undefined\" && logic.onPageLoad) logic.onPageLoad(this); },\n")
        for (h in tree.eventHandlers) {
            sb.append("  \"${h.handlerId}\": function(${handlerParam(h)}) {\n")
            h.jsLines.forEach { sb.append("    $it\n") }
            sb.append("  },\n")
        }
        sb.append("  __commit(field, value) {\n")
        sb.append("    const p = {};\n")
        sb.append("    p[field] = value;\n")
        sb.append("    this.setData(p);\n")
        sb.append("  },\n")
        sb.append("});\n")
        return sb.toString()
    }

    private fun handlerParam(h: EventHandler): String =
        if (h.kind == EventKind.INPUT_COMMIT || h.jsLines.any { it.contains("e.currentTarget") }) "e" else ""
}

object JsonGenerator {

    fun page(tree: UiTree): String {
        val title = pageTitle(tree.pageName)
        return "{\n  \"navigationBarTitleText\": \"$title\",\n  \"usingComponents\": {}\n}\n"
    }

    private fun pageTitle(pageName: String): String = when (pageName) {
        "home" -> "首页"
        "store" -> "门店"
        "cart" -> "购物车"
        "orders" -> "订单"
        "mine" -> "我的"
        else -> pageName.replaceFirstChar { it.uppercase() }
    }
}
