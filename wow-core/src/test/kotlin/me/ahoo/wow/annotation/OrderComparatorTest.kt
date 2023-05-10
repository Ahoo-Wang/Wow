/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.annotation

import me.ahoo.wow.api.annotation.ORDER_FIRST
import me.ahoo.wow.api.annotation.ORDER_LAST
import me.ahoo.wow.api.annotation.Order
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

internal class OrderComparatorTest {

    @Test
    fun compareAllUndefined() {
        assertThat(OrderComparator.compare(Any(), Any()), equalTo(0))
    }

    @Test
    fun compareBefore() {
        assertThat(OrderComparator.compare(Before, Undefined), equalTo(-1))
        assertThat(OrderComparator.compare(Undefined, Before), equalTo(1))
    }

    @Test
    fun compareAfter() {
        assertThat(OrderComparator.compare(After, Undefined), equalTo(1))
        assertThat(OrderComparator.compare(Undefined, After), equalTo(-1))
    }

    @Test
    fun compareOrder() {
        assertThat(OrderComparator.compare(OrderFirst, OrderLast), equalTo(-1))
        assertThat(OrderComparator.compare(OrderLast, OrderFirst), equalTo(1))
    }

    @Test
    fun compareBeforeFirst() {
        assertThat(OrderComparator.compare(Before, OrderFirst), equalTo(-1))
        val sortedList = listOf(OrderLast, OrderFirst, Before, After, Undefined).sortedWith(OrderComparator)
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
