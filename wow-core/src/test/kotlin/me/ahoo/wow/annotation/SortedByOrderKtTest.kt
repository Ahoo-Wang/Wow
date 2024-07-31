package me.ahoo.wow.annotation

import me.ahoo.wow.api.annotation.ORDER_FIRST
import me.ahoo.wow.api.annotation.ORDER_LAST
import me.ahoo.wow.api.annotation.Order
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

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
