// dsl-core：Compose 风格页面 API 契约
// 页面源码由编译器静态解析；这里的声明只用于 IDE 补全和独立 Kotlin 预览。
package compose.miniapp.dsl

// ---------- 注解 ----------

@Target(AnnotationTarget.FUNCTION)
annotation class EntryPoint(val path: String)

@Target(AnnotationTarget.FUNCTION)
annotation class Composable

// ---------- 页面作用域 ----------

class PageScope {
    fun navigateTo(path: String) {}
    fun navigateBack() {}
}

// ---------- 状态 ----------

class MutableState<T>(var value: T)

infix fun <T> MutableState<T>.by(ignored: Any?): T = value

class StateHolder

// ---------- 单位与样式 ----------

class Dp(val value: Double)
val Int.dp: Dp get() = Dp(toDouble())
val Double.dp: Dp get() = Dp(this)

class Sp(val value: Double)
val Int.sp: Sp get() = Sp(toDouble())

class TextStyle(
    val fontSize: Sp? = null,
    val bold: Boolean = false,
    val color: String? = null,
)

class Alignment
object Alignment {
    val Center = Alignment()
    val Start = Alignment()
    val End = Alignment()
}

object Arrangement {
    fun spacedBy(value: Dp): Dp = value
    val SpaceBetween = "space-between"
    val Center = "center"
    val End = "end"
}

object TextAlign {
    const val Center = "center"
    const val Start = "start"
    const val End = "end"
}

class Padding(val all: Dp? = null)

// ---------- 组件白名单 ----------

class ColumnScope
class RowScope

fun Column(
    padding: Dp? = null,
    spacing: Dp? = null,
    verticalArrangement: Any? = null,
    horizontalArrangement: Any? = null,
    modifier: Any? = null,
    child: ColumnScope.() -> Unit,
) {}

fun Row(
    align: Alignment? = null,
    padding: Dp? = null,
    spacing: Dp? = null,
    verticalArrangement: Any? = null,
    horizontalArrangement: Any? = null,
    modifier: Any? = null,
    child: RowScope.() -> Unit,
) {}

fun Text(
    text: Any,
    style: TextStyle? = null,
    fontSize: Sp? = null,
    fontWeight: Any? = null,
    color: Any? = null,
    textAlign: Any? = null,
    modifier: Any? = null,
) {}

fun Button(
    label: Any,
    onClick: () -> Unit = {},
    modifier: Any? = null,
) {}

fun Button(
    onClick: () -> Unit = {},
    modifier: Any? = null,
    content: () -> Unit,
) {}

fun Image(
    url: Any,
    mode: String = "aspectFill",
    lazyLoad: Boolean = false,
    modifier: Any? = null,
) {}

fun <T> List(
    items: kotlin.collections.List<T>,
    key: (T) -> Any? = { null },
    modifier: Any? = null,
    item: (T) -> Unit,
) {}

fun Input(
    value: String,
    onValueChange: (String) -> Unit = {},
    placeholder: String = "",
    modifier: Any? = null,
) {}

fun ScrollView(
    scrollY: Boolean = true,
    modifier: Any? = null,
    child: ColumnScope.() -> Unit,
) {}

fun Spacer(width: Dp? = null, height: Dp? = null) {}

fun IfBlock(condition: Boolean, then: () -> Unit) {}

// ---------- 事件与逻辑层 ----------

object logic {
    fun addToCart(id: Any, name: Any, price: Any) {}
    fun adjustQuantity(id: Any, delta: Any) {}
    fun checkout() {}
    fun chooseAddress() {}
    fun clearCart() {}
    fun login(phone: Any) {}
    fun openStore(id: Any) {}
    fun payOrder(id: Any) {}
    fun refresh() {}
    fun removeCoupon() {}
    fun selectCoupon(id: Any) {}
    fun submitAddress() {}
    fun toggleFavorite(id: Any) {}
    fun useCoupon(id: Any) {}
}
