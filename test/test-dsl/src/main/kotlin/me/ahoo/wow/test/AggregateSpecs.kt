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

import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CallerAdmission
import org.junit.jupiter.api.DynamicNode
import java.time.Instant
import java.util.stream.Stream

/**
 * Specifies an aggregate's behaviour as given events, a command and the expected outcome, against the real kernel
 * and an in-memory log:
 *
 * ```kotlin
 * aggregateSpec(cartModel) {
 *     given(CartCreated("ahoo"))
 *     whenCommand(AddItem("apple", 1)) {
 *         expectEvents(ItemAdded("apple", 1))
 *         expectState { items.assert().containsEntry("apple", 1) }
 *     }
 * }
 * ```
 *
 * The kernel checks that deciding never changes the state: a decide function or after hook that assigns state
 * fails the specification.
 *
 * Time stands still at [start] until [AggregateSpec.advanceTime] moves it: the time a decision reads
 * ([me.ahoo.wow.api.decide.DecideScope.now]), which its commit is recorded at, and the due times of
 * the commands the aggregate schedules, are read from it. Advancing the time sends the aggregate the
 * commands it scheduled for itself that are then due, as the runtime would, each with its timer's request id
 * (`timer#slot`) and its slot ([me.ahoo.wow.api.decide.Command.schedule]). A cron rule steps through its slots as the
 * runtime's firing loop does: advancing past several of them sends only the latest, the others counted as skipped,
 * unless the rule catches up ([me.ahoo.wow.api.schedule.Misfire.CATCH_UP]), which sends each in turn:
 *
 * ```kotlin
 * aggregateSpec(orderModel) {
 *     whenCommand(PlaceOrder(...)) {
 *         expectScheduled("pay-timeout", CancelOrder("unpaid"), ScheduleRule.after(30.minutes))
 *     }
 *     advanceTime(30.minutes) { expectEvents(OrderCancelled("unpaid")) }
 * }
 * ```
 *
 * The ids a decide function takes ([me.ahoo.wow.api.decide.DecideScope.ids]) are [SequentialIds]:
 * `id-1`, `id-2`… and `<aggregate>-1`… for aggregate ids, counted across the specification.
 *
 * Scenarios that share a prefix [AggregateSpec.fork] from it, at the current point, at one marked with
 * [AggregateSpec.ref], or after a command from inside its checks ([Expectations.fork]): each fork runs on its own
 * copy of the aggregate, its log, timers, reservations, ids and time, so its commands do not reach
 * the others, and nothing before the fork runs again: a domain service the decide functions call is not called
 * again for the prefix.
 *
 * The commands are sent by [caller], as the command gateway admits a caller's command: in its scope, by its
 * operator, with its application as the request fact `app_id` ([CallerAdmission]); none (an anonymous caller in no
 * scope) by default. The aggregate is in [tenantId], the caller's tenant by default.
 *
 * The whole specification is one test; [aggregateSpecTests] makes the same specification a tree of tests instead.
 */
fun <S : Any> aggregateSpec(
    model: AggregateModel<S>,
    id: String = "aggregate-1",
    caller: Caller? = null,
    tenantId: String = caller?.tenantId ?: DEFAULT_TENANT,
    start: Instant = DEFAULT_START,
    block: AggregateSpec<S>.() -> Unit,
) {
    AggregateSpec(model, model.type.ref(tenantId, id), start).also { it.caller = caller }.block()
}

/**
 * The specification [aggregateSpec] runs, as a tree of tests for a JUnit `@TestFactory`: each
 * [AggregateSpec.whenCommand], [AggregateSpec.advanceTime] and [AggregateSpec.expectNothingDue] is a test of its own,
 * and each [AggregateSpec.fork] a container of the tests it runs, named by the fork. A failing check fails its own
 * test only: the specification goes on, so the other steps and forks still run and report on their own.
 *
 * ```kotlin
 * @TestFactory
 * fun order() = aggregateSpecTests(orderModel) {
 *     whenCommand(PlaceOrder(...)) {
 *         expectEventTypes(OrderPlaced::class)
 *         fork("paid") { whenCommand(PayOrder(...)) { expectEventTypes(OrderPaid::class) } }
 *         fork("cancelled") { whenCommand(CancelOrder(...)) { expectEventTypes(OrderCancelled::class) } }
 *     }
 * }
 * ```
 *
 * The specification runs as the tree is built, as 9.x's did; the tests report what each step found. An exception
 * outside a step (a [AggregateSpec.given] that cannot commit, say) ends its fork, or the specification, and is
 * reported as a failing test `Error`.
 */
fun <S : Any> aggregateSpecTests(
    model: AggregateModel<S>,
    id: String = "aggregate-1",
    caller: Caller? = null,
    tenantId: String = caller?.tenantId ?: DEFAULT_TENANT,
    start: Instant = DEFAULT_START,
    block: AggregateSpec<S>.() -> Unit,
): Stream<DynamicNode> {
    val tree = TestTree()
    val spec = AggregateSpec(model, model.type.ref(tenantId, id), start, tree = tree).also { it.caller = caller }
    tree.run { spec.block() }
    return tree.nodes.stream()
}

/**
 * The tenant a specification's aggregates are in by default, the default tenant of a caller ([Caller.DEFAULT_TENANT]):
 * that of [aggregateSpec], [ScheduleSpec]'s instances and [ProcessorSpec.givenAggregate].
 */
const val DEFAULT_TENANT: String = Caller.DEFAULT_TENANT

/** Where the time of an [aggregateSpec] starts by default. */
val DEFAULT_START: Instant = Instant.parse("2026-01-01T00:00:00Z")

/** The id of the aggregate [AggregateSpec.givenReservation] gives a key to by default. */
const val ANOTHER_AGGREGATE: String = "another-aggregate"
