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

import me.ahoo.wow.test.aggregate.dsl.AggregateDsl
import me.ahoo.wow.test.aggregate.dsl.DefaultAggregateDsl
import me.ahoo.wow.test.dsl.AbstractDynamicTestBuilder
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.TestFactory
import java.lang.reflect.ParameterizedType
import java.util.stream.Stream

/**
 * Base class for writing aggregate specification tests using a domain-specific language (DSL).
 *
 * AggregateSpec provides a declarative way to define comprehensive test scenarios for domain aggregates
 * using the Given/When/Expect pattern. It leverages JUnit 5's dynamic test capabilities to generate
 * multiple test cases from a single specification, including branching scenarios via forks.
 *
 * This class is designed for behavior-driven development (BDD) style testing of aggregates,
 * allowing complex state transitions and command validations to be expressed fluently.
 *
 * Key features:
 * - Declarative test definition using DSL
 * - Support for branching test scenarios with forks
 * - Automatic generation of dynamic JUnit 5 tests
 * - Type-safe aggregate testing with generics
 * - Integration with dependency injection for mocking services
 *
 * Example usage:
 * ```kotlin
 * class CartSpec : AggregateSpec<Cart, CartState>({
 *     on {
 *         val addCartItem = AddCartItem(productId = "item1", quantity = 1)
 *         givenOwnerId("owner123")
 *         whenCommand(addCartItem) {
 *             expectNoError()
 *             expectEventType(CartItemAdded::class)
 *             expectState { items.assert().hasSize(1) }
 *
 *             fork("Remove Item") {
 *                 val removeCommand = RemoveCartItem(productIds = setOf("item1"))
 *                 whenCommand(removeCommand) {
 *                     expectEventType(CartItemRemoved::class)
 *                     expectState { items.assert().isEmpty() }
 *                 }
 *             }
 *         }
 *     }
 * })
 * ```
 *
 * @param C the type of the command aggregate
 * @param S the type of the state aggregate
 * @param block the DSL block that defines the test scenarios
 * @author ahoo wang
 */
abstract class AggregateSpec<C : Any, S : Any>(
    private val block: AggregateDsl<S>.() -> Unit
) : AbstractDynamicTestBuilder() {
    /**
     * The command aggregate type resolved from the generic type parameters.
     *
     * This property uses reflection to extract the command aggregate class from the generic
     * type arguments of the subclass. It's used internally to configure the test DSL
     * with the correct aggregate type.
     *
     * @return the Class representing the command aggregate type
     * @throws ClassCastException if the generic type cannot be resolved to a Class
     */
    val commandAggregateType: Class<C>
        get() {
            val type = this::class.java.genericSuperclass as ParameterizedType
            @Suppress("UNCHECKED_CAST")
            return type.actualTypeArguments[0] as Class<C>
        }

    /**
     * Executes the aggregate specification and generates dynamic test nodes.
     *
     * This method is annotated with @TestFactory and serves as the entry point for JUnit 5
     * to discover and execute the dynamic tests defined in the specification block.
     * It creates a DefaultAggregateDsl instance, executes the user-defined test block,
     * and returns a stream of DynamicNode objects representing the individual test cases.
     *
     * The method handles the conversion of the DSL specification into executable JUnit tests,
     * including any forked test branches and nested scenarios.
     *
     * Example generated tests:
     * - "Add Cart Item"
     * - "Add Cart Item > Remove Item"
     * - "Add Cart Item > Delete Aggregate"
     * - "Add Cart Item > Delete Aggregate > Operate on Deleted"
     *
     * @return a Stream of DynamicNode objects representing the generated test cases
     * @throws Exception if test execution fails or DSL configuration is invalid
     */
    @TestFactory
    fun execute(): Stream<DynamicNode> {
        val aggregateDsl = DefaultAggregateDsl<C, S>(commandAggregateType)
        block(aggregateDsl)
        return aggregateDsl.dynamicNodes.stream()
    }
}
