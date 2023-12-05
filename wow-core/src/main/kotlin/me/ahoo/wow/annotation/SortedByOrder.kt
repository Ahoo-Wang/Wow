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

import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.infra.reflection.AnnotationScanner.scan

fun <T : Any> Iterable<T>.sortedByOrder(): List<T> {
    val sortedByOrderList = this.map {
        val order: Order = it.javaClass.scan() ?: Order()
        it to order
    }.sortedBy { it.second.value }

    val sortedList = sortedByOrderList.toMutableList()

    sortedByOrderList.forEach { current ->
        sortedList.moveToBefore(current)
        sortedList.moveToAfter(current)
    }
    return sortedList.map { it.first }
}

private fun <T : Any> MutableList<Pair<T, Order>>.moveToBefore(current: Pair<T, Order>) {
    val beforeValues = current.second.before
    for (beforeClass in beforeValues) {
        val beforeIndex = indexOfFirst { it.first::class == beforeClass }
        if (beforeIndex == -1) {
            continue
        }
        val currentIndex = indexOf(current)
        if (currentIndex < beforeIndex) {
            continue
        }
        removeAt(currentIndex)
        add(beforeIndex, current)
    }
}


private fun <T : Any> MutableList<Pair<T, Order>>.moveToAfter(current: Pair<T, Order>) {
    val afterValues = current.second.after
    for (afterClass in afterValues) {
        val afterIndex = indexOfFirst { it.first::class == afterClass }
        if (afterIndex == -1) {
            continue
        }
        val currentIndex = indexOf(current)
        if (currentIndex > afterIndex) {
            continue
        }
        add(afterIndex + 1, current)
        removeAt(currentIndex)
    }
}

