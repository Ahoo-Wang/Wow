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

package me.ahoo.wow.test

import me.ahoo.wow.test.dsl.AbstractDynamicTestBuilder
import me.ahoo.wow.test.saga.stateless.dsl.DefaultStatelessSagaDsl
import me.ahoo.wow.test.saga.stateless.dsl.StatelessSagaDsl
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.TestFactory
import java.lang.reflect.ParameterizedType
import java.util.stream.Stream

/**
 * Abstract base class for writing stateless saga specification tests using JUnit 5.
 *
 * This class provides a DSL-based framework for testing stateless sagas by defining
 * scenarios with events and expected command outcomes. It uses JUnit 5's @TestFactory
 * to generate dynamic tests from the specification.
 *
 * The primary purpose is to facilitate behavior-driven testing of sagas, allowing
 * developers to specify expected command emissions in response to domain events.
 * It enables testing saga logic in isolation without requiring full infrastructure setup.
 *
 * Example usage:
 * ```
 * class CartSagaSpec : SagaSpec<CartSaga>({
 *     on {
 *         val ownerId = generateGlobalId()
 *         val orderItem = OrderItem(
 *             id = generateGlobalId(),
 *             productId = generateGlobalId(),
 *             price = BigDecimal.valueOf(10),
 *             quantity = 10,
 *         )
 *         whenEvent(
 *             event = mockk<OrderCreated> {
 *                 every { items } returns listOf(orderItem)
 *                 every { fromCart } returns true
 *             },
 *             ownerId = ownerId
 *         ) {
 *             expectCommandType(RemoveCartItem::class)
 *             expectCommand<RemoveCartItem> {
 *                 aggregateId.id.assert().isEqualTo(ownerId)
 *                 body.productIds.assert().hasSize(1)
 *                 body.productIds.assert().first().isEqualTo(orderItem.productId)
 *             }
 *         }
 *     }
 * })
 * ```
 *
 * @param T The type of the saga being tested, must be a class that implements the saga logic.
 * @property block The test specification block that defines the saga test scenarios.
 * This is a lambda with receiver of type StatelessSagaDsl<T>, where T is the saga type.
 * It contains the test definitions using the DSL methods.
 */
abstract class SagaSpec<T : Any>(
    private val block: StatelessSagaDsl<T>.() -> Unit
) : AbstractDynamicTestBuilder() {
    /**
     * The processor type of the saga being tested, extracted from the generic type parameter.
     *
     * This property uses reflection to determine the saga class type from the subclass's
     * generic type argument. It accesses the ParameterizedType of the superclass to retrieve
     * the actual type argument at index 0, which corresponds to T.
     *
     * @return The Class object for the saga type T.
     * @throws ClassCastException if the generic superclass is not a ParameterizedType.
     */
    val processorType: Class<T>
        get() {
            val type = this::class.java.genericSuperclass as ParameterizedType
            @Suppress("UNCHECKED_CAST")
            return type.actualTypeArguments[0] as Class<T>
        }

    /**
     * Executes the saga test specification and returns a stream of dynamic test nodes.
     *
     * This method is annotated with @TestFactory and is called by JUnit 5 to generate
     * dynamic tests. It creates a DSL instance using DefaultStatelessSagaDsl, executes
     * the test block by invoking it on the DSL, and returns the resulting dynamic test nodes.
     * Each dynamic node represents a test scenario defined in the specification block.
     *
     * @return A stream of [DynamicNode] instances representing the generated tests.
     * The stream contains all test cases built from the DSL specification.
     */
    @TestFactory
    fun execute(): Stream<DynamicNode> {
        val statelessSagaDsl = DefaultStatelessSagaDsl(processorType)
        block(statelessSagaDsl)
        return statelessSagaDsl.dynamicNodes.stream()
    }
}
