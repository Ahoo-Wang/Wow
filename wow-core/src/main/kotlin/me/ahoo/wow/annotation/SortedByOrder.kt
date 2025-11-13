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

import me.ahoo.wow.api.Ordered
import me.ahoo.wow.api.annotation.Order
import java.lang.reflect.AnnotatedElement
import kotlin.reflect.KAnnotatedElement
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation

/**
 * Gets the Kotlin class representation of this object.
 *
 * This private utility function handles different types of objects and returns
 * their corresponding KClass, supporting both Kotlin and Java class types.
 *
 * @param T the type of the object
 * @return the KClass representation of this object
 */
private fun <T : Any> T.getKClass(): KClass<*> =
    when (this) {
        is KClass<*> -> {
            this
        }

        is Class<*> -> {
            this.kotlin
        }

        else -> {
            this.javaClass.kotlin
        }
    }

/**
 * Retrieves the order configuration for this object.
 *
 * This method checks for order information in the following priority:
 * 1. If the object implements Ordered interface, uses its order property
 * 2. If it's a KAnnotatedElement (Kotlin), looks for @Order annotation
 * 3. If it's an AnnotatedElement (Java), looks for @Order annotation
 * 4. Falls back to class-level @Order annotation
 * 5. Returns Order.DEFAULT if no order is specified
 *
 * @param T the type of the object
 * @return the Order configuration for this object
 */
private fun <T : Any> T.getOrder(): Order {
    if (this is Ordered) {
        return order
    }
    if (this is KAnnotatedElement) {
        return findAnnotation<Order>() ?: Order.DEFAULT
    }
    if (this is AnnotatedElement) {
        return this.getAnnotation(Order::class.java) ?: Order.DEFAULT
    }
    return this.javaClass.getAnnotation(Order::class.java) ?: Order.DEFAULT
}

/**
 * Sorts an iterable collection based on Order annotations and dependencies.
 *
 * This extension function sorts the collection by considering both the numeric order value
 * and the before/after dependencies specified in @Order annotations. Items are first sorted
 * by their order value, then repositioned according to their before/after relationships.
 *
 * Example usage:
 * ```kotlin
 * @Order(1)
 * class FirstProcessor
 *
 * @Order(2, before = [ThirdProcessor::class])
 * class SecondProcessor
 *
 * @Order(3)
 * class ThirdProcessor
 *
 * val processors = listOf(ThirdProcessor(), FirstProcessor(), SecondProcessor())
 * val sorted = processors.sortedByOrder()
 * // Result: [FirstProcessor, SecondProcessor, ThirdProcessor]
 * ```
 *
 * @param T the type of elements in the collection
 * @return a new list sorted by order with dependencies resolved
 * @see Order
 */
fun <T : Any> Iterable<T>.sortedByOrder(): List<T> {
    val sortedByOrderList =
        this
            .map {
                val order: Order = it.getOrder()
                it to order
            }.sortedBy { it.second.value }

    val sortedList = sortedByOrderList.toMutableList()

    sortedByOrderList.forEach { current ->
        sortedList.moveToBefore(current)
        sortedList.moveToAfter(current)
    }
    return sortedList.map { it.first }
}

/**
 * Moves the current item to its correct position relative to items it should come before.
 *
 * This private extension function repositions the current item in the list so that it appears
 * before all items specified in the 'before' array of its Order annotation.
 *
 * @param T the type of elements in the list
 * @param current the item to reposition along with its order configuration
 */
private fun <T : Any> MutableList<Pair<T, Order>>.moveToBefore(current: Pair<T, Order>) {
    val beforeValues = current.second.before
    for (beforeClass in beforeValues) {
        val beforeIndex = indexOfFirst { it.first.getKClass() == beforeClass }
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

/**
 * Moves the current item to its correct position relative to items it should come after.
 *
 * This private extension function repositions the current item in the list so that it appears
 * after all items specified in the 'after' array of its Order annotation.
 *
 * @param T the type of elements in the list
 * @param current the item to reposition along with its order configuration
 */
private fun <T : Any> MutableList<Pair<T, Order>>.moveToAfter(current: Pair<T, Order>) {
    val afterValues = current.second.after
    for (afterClass in afterValues) {
        val afterIndex = indexOfFirst { it.first.getKClass() == afterClass }
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
