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

import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.ExecutionSagaContext
import me.ahoo.wow.kernel.ProcessorBinding
import org.junit.jupiter.api.DynamicNode
import java.util.stream.Stream

/**
 * Specifies a saga's behaviour: given a commit of a stream, its own context's or one it reads from another context
 * (a [StateEvent], built with [stateEvent]), or the rejection or the commit of a command it derived, the commands it
 * derives and the commands it schedules. [handlers] are the handlers of the saga under test, from the function the compiler
 * generated for it (`<processor>Handlers`, `orderSagaHandlers` for `OrderSaga`), which constructs that saga only,
 * with its own services:
 *
 * ```kotlin
 * sagaSpec(orderSagaHandlers()) {
 *     whenEvent(stateEvent(order, OrderState("order-1"), orderCreated)) {
 *         expectCommands(ReserveStock("order-1", 2))
 *         expectDeterministic()
 *         whenRejected(commands.single(), code = "inventory.stock.insufficient") {
 *             expectCommand(CancelOrder("..."), to = order)
 *         }
 *         whenCommitted(commands.single(), version = 3) {
 *             expectCommand(ConfirmStock("product-1"), to = order)
 *         }
 *     }
 * }
 * ```
 *
 * Each call of a handler gets a fresh [ExecutionSagaContext], as the runtime gives each handling of a commit, so the
 * ids the saga derives are the same on every call for the same commit. Its [ids][me.ahoo.wow.api.subscribe.SagaContext.ids]
 * are [SequentialIds], counted anew for each call: `id-1`… and `<aggregate>-1`….
 *
 * The synchronous calls a saga makes ([me.ahoo.wow.api.subscribe.SagaContext.call]) are answered by
 * [answerCalls], committed at version 1 by default ([committedResult]); [ProcessorExpectations.calls] lists them.
 */
fun sagaSpec(handlers: List<ProcessorBinding<*>>, block: ProcessorSpec.() -> Unit) {
    ProcessorSpec(handlers, FunctionKind.SAGA).block()
}

/**
 * The specification [sagaSpec] runs, as a tree of tests for a JUnit `@TestFactory`: each
 * [ProcessorSpec.whenEvent], [ProcessorSpec.whenRejected] and [ProcessorSpec.whenCommitted] is a test of its own, and
 * each [ProcessorSpec.fork] a container named by it; a failing check fails its own test only, as
 * [aggregateSpecTests] does:
 *
 * ```kotlin
 * @TestFactory
 * fun orderSaga() = sagaSpecTests(orderSagaHandlers()) {
 *     whenEvent(stateEvent(order, OrderState("order-1"), orderCreated)) {
 *         expectCommands(ReserveStock("order-1", 2))
 *         fork("out of stock") {
 *             whenRejected(commands.single(), code = "inventory.stock.insufficient") { expectCommandTypes(CancelOrder::class) }
 *         }
 *         fork("reserved") { whenCommitted(commands.single()) { expectCommandTypes(ConfirmStock::class) } }
 *     }
 * }
 * ```
 */
fun sagaSpecTests(handlers: List<ProcessorBinding<*>>, block: ProcessorSpec.() -> Unit): Stream<DynamicNode> =
    processorSpecTests(handlers, FunctionKind.SAGA, block)

/**
 * Specifies a projection's behaviour: given a commit (a [StateEvent], built with [stateEvent]), what its handlers
 * write. [handlers] are the projection's, from the function the compiler generated for it
 * (`<processor>Handlers`), with a fake of the port it writes to, which the expectations then read:
 *
 * ```kotlin
 * val views = InMemoryOrderViews()
 * projectionSpec(orderViewProjectionHandlers(views)) {
 *     whenEvent(stateEvent(order, state, orderCreated)) {
 *         expectHandledBy("onOrder")
 *         views["order-1"]!!.status.assert().isEqualTo("CREATED")
 *     }
 * }
 * ```
 */
fun projectionSpec(handlers: List<ProcessorBinding<*>>, block: ProcessorSpec.() -> Unit) {
    ProcessorSpec(handlers, FunctionKind.PROJECT).block()
}

/** The specification [projectionSpec] runs, as a tree of tests for a JUnit `@TestFactory`, as [sagaSpecTests]. */
fun projectionSpecTests(handlers: List<ProcessorBinding<*>>, block: ProcessorSpec.() -> Unit): Stream<DynamicNode> =
    processorSpecTests(handlers, FunctionKind.PROJECT, block)

/** Specifies a reactor's behaviour as [projectionSpec] does a projection's: given a commit, its side effects. */
fun reactorSpec(handlers: List<ProcessorBinding<*>>, block: ProcessorSpec.() -> Unit) {
    ProcessorSpec(handlers, FunctionKind.REACT).block()
}

/** The specification [reactorSpec] runs, as a tree of tests for a JUnit `@TestFactory`, as [sagaSpecTests]. */
fun reactorSpecTests(handlers: List<ProcessorBinding<*>>, block: ProcessorSpec.() -> Unit): Stream<DynamicNode> =
    processorSpecTests(handlers, FunctionKind.REACT, block)

private fun processorSpecTests(
    handlers: List<ProcessorBinding<*>>,
    kind: FunctionKind,
    block: ProcessorSpec.() -> Unit,
): Stream<DynamicNode> {
    val tree = TestTree()
    tree.run { ProcessorSpec(handlers, kind, tree = tree).block() }
    return tree.nodes.stream()
}
