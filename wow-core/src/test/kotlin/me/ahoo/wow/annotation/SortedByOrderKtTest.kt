package me.ahoo.wow.annotation

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

