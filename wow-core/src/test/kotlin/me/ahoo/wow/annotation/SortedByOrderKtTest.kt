package me.ahoo.wow.annotation

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.ORDER_FIRST
import me.ahoo.wow.api.annotation.ORDER_LAST
import me.ahoo.wow.api.annotation.Order
import org.junit.jupiter.api.Test
import kotlin.reflect.jvm.javaMethod

class SortedByOrderKtTest {

    @Test
    fun `should sort any objects`() {
        val sortedList = listOf(Any(), Any()).sortedByOrder()
        sortedList.assert().hasSize(2)
    }

    @Test
    fun `should sort by order value`() {
        val sortedList = listOf(OrderLast, OrderFirst).sortedByOrder()
        sortedList.assert().hasSize(2)
        sortedList.assert().containsSequence(OrderFirst, OrderLast)
    }

    @Test
    fun `should sort by order before`() {
        val sortedList = listOf(Undefined, Before).sortedByOrder()
        sortedList.assert().hasSize(2)
        sortedList.assert().containsSequence(Before, Undefined)
    }

    @Test
    fun `should sort by order after`() {
        val sortedList = listOf(After, Undefined).sortedByOrder()
        sortedList.assert().hasSize(2)
        sortedList.assert().containsSequence(Undefined, After)
    }

    @Test
    fun `should sort all items by order`() {
        val sortedList = listOf(OrderLast, OrderFirst, Before, After, Undefined).sortedByOrder()
        sortedList.assert().hasSize(5)
        sortedList.assert().containsSequence(Before, OrderFirst, OrderLast, Undefined, After)
    }

    @Test
    fun `should sort classes by order`() {
        val sortedList = listOf(
            OrderLast::class.java,
            OrderFirst::class.java,
            Before::class.java,
            After::class.java,
            Undefined::class.java
        ).sortedByOrder()
        sortedList.assert().hasSize(5)
        sortedList.assert().containsSequence(
            Before::class.java,
            OrderFirst::class.java,
            OrderLast::class.java,
            Undefined::class.java,
            After::class.java
        )
    }

    @Test
    fun `should sort kclasses by order`() {
        val sortedList = listOf(
            OrderLast::class,
            OrderFirst::class,
            Before::class,
            After::class,
            Undefined::class
        ).sortedByOrder()
        sortedList.assert().hasSize(5)
        sortedList.assert().containsSequence(
            Before::class,
            OrderFirst::class,
            OrderLast::class,
            Undefined::class,
            After::class
        )
    }

    @Test
    fun `should sort methods by order`() {
        val sortedList = listOf(
            OrderMethods::orderDefault.javaMethod!!,
            OrderMethods::orderFirst.javaMethod!!,
            OrderMethods::orderLast.javaMethod!!
        ).sortedByOrder()
        sortedList.assert().containsSequence(
            OrderMethods::orderFirst.javaMethod,
            OrderMethods::orderDefault.javaMethod,
            OrderMethods::orderLast.javaMethod
        )
    }
}

@Order(before = [Undefined::class, OrderFirst::class])
object Before
object Undefined

@Order(after = [Undefined::class])
object After

@Order(ORDER_FIRST)
object OrderFirst

@Order(ORDER_LAST, before = [Undefined::class, After::class])
object OrderLast

@Suppress("FunctionOnlyReturningConstant")
object OrderMethods {
    @Order(ORDER_FIRST)
    fun orderFirst(): String {
        return "orderFirst"
    }

    fun orderDefault(): String {
        return "orderFirst"
    }

    @Order(ORDER_LAST)
    fun orderLast(): String {
        return "orderLast"
    }
}
