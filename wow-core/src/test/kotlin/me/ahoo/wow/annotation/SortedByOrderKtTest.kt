package me.ahoo.wow.annotation

import me.ahoo.wow.api.annotation.ORDER_FIRST
import me.ahoo.wow.api.annotation.ORDER_LAST
import me.ahoo.wow.api.annotation.Order
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import kotlin.reflect.jvm.javaMethod

class SortedByOrderKtTest {

    @Test
    fun sortAny() {
        val sortedList = listOf(Any(), Any()).sortedByOrder()
        assertThat(sortedList, hasSize(2))
    }

    @Test
    fun sortOrderValue() {
        val sortedList = listOf(OrderLast, OrderFirst).sortedByOrder()
        assertThat(sortedList, hasSize(2))
        assertThat(sortedList, contains(OrderFirst, OrderLast))
    }

    @Test
    fun sortOrderBefore() {
        val sortedList = listOf(Undefined, Before).sortedByOrder()
        assertThat(sortedList, hasSize(2))
        assertThat(sortedList, contains(Before, Undefined))
    }

    @Test
    fun sortOrderAfter() {
        val sortedList = listOf(After, Undefined).sortedByOrder()
        assertThat(sortedList, hasSize(2))
        assertThat(sortedList, contains(Undefined, After))
    }

    @Test
    fun sortByOrder() {
        val sortedList = listOf(OrderLast, OrderFirst, Before, After, Undefined).sortedByOrder()
        assertThat(sortedList, hasSize(5))
        assertThat(sortedList, contains(Before, OrderFirst, OrderLast, Undefined, After))
    }

    @Test
    fun sortByOrderClass() {
        val sortedList = listOf(
            OrderLast::class.java,
            OrderFirst::class.java,
            Before::class.java,
            After::class.java,
            Undefined::class.java
        ).sortedByOrder()
        assertThat(sortedList, hasSize(5))
        assertThat(
            sortedList,
            contains(
                Before::class.java,
                OrderFirst::class.java,
                OrderLast::class.java,
                Undefined::class.java,
                After::class.java
            )
        )
    }

    @Test
    fun sortByOrderKClass() {
        val sortedList = listOf(
            OrderLast::class,
            OrderFirst::class,
            Before::class,
            After::class,
            Undefined::class
        ).sortedByOrder()
        assertThat(sortedList, hasSize(5))
        assertThat(
            sortedList,
            contains(
                Before::class,
                OrderFirst::class,
                OrderLast::class,
                Undefined::class,
                After::class
            )
        )
    }

    @Test
    fun sortMethod() {
        val sortedList = listOf(
            OrderMethods::orderDefault.javaMethod!!,
            OrderMethods::orderFirst.javaMethod!!,
            OrderMethods::orderLast.javaMethod!!
        ).sortedByOrder()
        assertThat(
            sortedList,
            contains(
                OrderMethods::orderFirst.javaMethod,
                OrderMethods::orderDefault.javaMethod,
                OrderMethods::orderLast.javaMethod
            )
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
