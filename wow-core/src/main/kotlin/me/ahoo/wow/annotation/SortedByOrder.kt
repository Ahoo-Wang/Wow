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
import java.util.PriorityQueue
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
 * Sorts the elements by their [Order]: a topological sort of the `before`/`after` constraints, breaking ties by
 * [Order.value] and then by input order.
 *
 * - `before = [X::class]` places the element before every element whose class is `X`; `after = [X::class]` after
 *   every such element. A constraint naming a class that is not in the collection is ignored.
 * - Among the elements whose constraints are satisfied, the one with the lowest value comes first; equal values keep
 *   their input order. Without constraints this is a stable sort by value.
 * - Constraints that form a cycle fail fast with an [IllegalStateException] that names the cycle.
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
 * @throws IllegalStateException when the `before`/`after` constraints form a cycle
 * @see Order
 */
fun <T : Any> Iterable<T>.sortedByOrder(): List<T> {
    val nodes = mapIndexed { index, element -> OrderNode(element, element.getOrder(), element.getKClass(), index) }
    if (nodes.size < 2) {
        return nodes.map { it.element }
    }
    val byClass = nodes.groupBy { it.kClass }
    // successors[i]: the nodes that must come after node i.
    val successors = Array(nodes.size) { LinkedHashSet<OrderNode<T>>() }
    val inDegree = IntArray(nodes.size)
    fun link(first: OrderNode<T>, second: OrderNode<T>) {
        if (first !== second && successors[first.index].add(second)) {
            inDegree[second.index]++
        }
    }
    for (node in nodes) {
        node.order.before.forEach { target -> byClass[target]?.forEach { link(node, it) } }
        node.order.after.forEach { target -> byClass[target]?.forEach { link(it, node) } }
    }
    val ready = PriorityQueue<OrderNode<T>>(ORDER_NODE_COMPARATOR)
    nodes.filter { inDegree[it.index] == 0 }.forEach { ready.add(it) }
    val sorted = ArrayList<T>(nodes.size)
    while (ready.isNotEmpty()) {
        val node = ready.poll()
        sorted.add(node.element)
        for (successor in successors[node.index]) {
            if (--inDegree[successor.index] == 0) {
                ready.add(successor)
            }
        }
    }
    check(sorted.size == nodes.size) {
        "@Order before/after constraints form a cycle: " +
            findCycle(nodes.filter { inDegree[it.index] > 0 }, successors).joinToString(" -> ") { it.kClass.java.name }
    }
    return sorted
}

private class OrderNode<T : Any>(val element: T, val order: Order, val kClass: KClass<*>, val index: Int)

private val ORDER_NODE_COMPARATOR: Comparator<OrderNode<*>> =
    compareBy<OrderNode<*>> { it.order.value }.thenBy { it.index }

/**
 * A cycle among [remaining], the nodes a topological sort could not place: each of them has a predecessor among them,
 * so walking predecessors from any of them must revisit one. Returns the cycle in constraint order, closed.
 */
private fun <T : Any> findCycle(
    remaining: List<OrderNode<T>>,
    successors: Array<LinkedHashSet<OrderNode<T>>>
): List<OrderNode<T>> {
    val remainingSet = remaining.toSet()
    val predecessor = HashMap<OrderNode<T>, OrderNode<T>>()
    for (node in remaining) {
        for (successor in successors[node.index]) {
            if (successor in remainingSet) {
                predecessor.putIfAbsent(successor, node)
            }
        }
    }
    val path = LinkedHashSet<OrderNode<T>>()
    var current = remaining.first()
    while (path.add(current)) {
        current = predecessor.getValue(current)
    }
    val cycle = path.toList().dropWhile { it !== current }.reversed()
    return cycle + cycle.first()
}
