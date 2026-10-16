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

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.api.schedule.ScheduleCancelled
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.ScheduleSet
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.spi.json.WowJson
import kotlin.reflect.KClass

/**
 * The checks of a command's outcome, or of the exception its decide function threw ([expectError]). [version] is
 * the aggregate's version after the command and [stateAt] reads its committed state at a version: [expectState]
 * reads the state the aggregate is in after a command that committed nothing.
 *
 * The event checks ([expectEvents], [expectEventTypes], [expectEvent]) read the events the decision committed beside
 * its schedules: the system events `wow.ScheduleSet` and `wow.ScheduleCancelled` (decision D198) are checked by
 * [expectScheduled] and [expectScheduleCancelled].
 *
 * A rejection is a value, not an exception, so a scenario that does not look at it would pass whatever the command
 * did (finding A4-14): a rejected command fails the scenario unless [expectRejected] checks it, or the scenario reads
 * the [outcome] itself.
 */
@Suppress("TooManyFunctions") // The DSL's expectations.
class Expectations<S : Any> @InternalWowApi constructor(
    private val model: AggregateModel<S>,
    private val codec: EventCodec,
    private val result: Result<CommandOutcome>,
    private val version: Int,
    private val stateAt: (Int) -> S,
    private val tagsAt: (Int) -> Map<String, List<String>> = { emptyMap() },
) {
    private var errorChecked = false
    private var outcomeChecked = false

    /** The command's outcome (`Committed`, `Unchanged`, `Rejected`, …); a failure when its decide function threw. */
    val outcome: CommandOutcome
        get() = decidedOutcome.also { outcomeChecked = true }

    private val decidedOutcome: CommandOutcome
        get() = result.getOrElse { throw AssertionError("Expected an outcome but the command threw $it.", it) }

    /** What the command's decide function threw; null when it returned a decision. */
    val error: Throwable? get() = result.exceptionOrNull()

    /** The command committed exactly [events], in order. */
    fun expectEvents(vararg events: Any) {
        val committed = decided()
        if (committed != events.toList()) {
            throw AssertionError("Expected events ${events.toList()} but got $committed.")
        }
    }

    /** The command committed events of exactly these types, in order. */
    fun expectEventTypes(vararg types: KClass<*>) = expectEventTypes(*types.map { it.java }.toTypedArray())

    /** The command committed events of exactly these types, in order. */
    fun expectEventTypes(vararg types: Class<*>) {
        val committed = decided().map { it.javaClass }
        if (committed != types.toList()) {
            throw AssertionError(
                "Expected event types ${types.map { it.simpleName }} but got ${committed.map { it.simpleName }}.",
            )
        }
    }

    /** The command committed exactly one event, an [E], which [check] checks. */
    inline fun <reified E : Any> expectEvent(noinline check: E.() -> Unit = {}): E = expectEvent(E::class, check)

    /** The command committed exactly one event, of [type], which [check] checks. */
    fun <E : Any> expectEvent(type: KClass<E>, check: E.() -> Unit = {}): E {
        val committed = decided()
        val event = committed.singleOrNull()
            ?: throw AssertionError("Expected one ${type.simpleName} but got $committed.")
        if (!type.isInstance(event)) {
            throw AssertionError("Expected a ${type.simpleName} but got $event.")
        }
        @Suppress("UNCHECKED_CAST")
        return (event as E).apply(check)
    }

    /** The command was accepted and changed nothing. */
    fun expectUnchanged() {
        val outcome = decidedOutcome
        if (outcome !is CommandOutcome.Unchanged) throw AssertionError("Expected no change but got $outcome.")
    }

    /** The decide function threw nothing and the command was not rejected. */
    fun expectNoError() {
        error?.let { throw AssertionError("Expected no error but the command threw $it.", it) }
        val outcome = decidedOutcome
        if (outcome is CommandOutcome.Rejected) throw AssertionError("Expected no error but got $outcome.")
    }

    /** The command was rejected with [code]; [check] checks the rejection, its message say. */
    fun expectRejected(code: String, check: Rejection.() -> Unit = {}) {
        val outcome = decidedOutcome
        outcomeChecked = true
        val rejected = outcome as? CommandOutcome.Rejected ?: throw AssertionError("Expected a rejection but got $outcome.")
        if (rejected.rejection.code != code) {
            throw AssertionError("Expected rejection $code but got ${rejected.rejection}.")
        }
        rejected.rejection.check()
    }

    /**
     * The command's decide function threw a [T] (9.x's `expectError`), which [check] checks: nothing was written.
     * A rejection is not an error: [expectRejected] checks it.
     */
    inline fun <reified T : Throwable> expectError(noinline check: T.() -> Unit = {}): T = expectError(T::class, check)

    /** The command's decide function threw a [type], which [check] checks. */
    fun <T : Throwable> expectError(type: KClass<T>, check: T.() -> Unit = {}): T {
        errorChecked = true
        val thrown = error ?: throw AssertionError("Expected a ${type.simpleName} but got $decidedOutcome.")
        if (!type.isInstance(thrown)) {
            throw AssertionError("Expected a ${type.simpleName} but the command threw $thrown.", thrown)
        }
        @Suppress("UNCHECKED_CAST")
        return (thrown as T).apply(check)
    }

    /**
     * The command scheduled [command] under [key] (decision D198), for [target] (the aggregate itself by default), due
     * as [rule] says when it is given: it committed `wow.ScheduleSet`.
     */
    @Suppress("ThrowsCount")
    fun expectScheduled(key: String, command: Any, rule: ScheduleRule? = null, target: AggregateRef? = null) {
        val stream = committed().stream
        val schedules = scheduleEvents()
        val set = schedules.filterIsInstance<ScheduleSet>().firstOrNull { it.key == key }
            ?: throw AssertionError("Expected a schedule $key but got $schedules.")
        val scheduled = codec.decodeTree(WowJson.mapper.readTree(set.command), command.javaClass)
        if (scheduled != command) {
            throw AssertionError("Expected $key to send $command but it sends $scheduled.")
        }
        if (rule != null && set.rule != rule) {
            throw AssertionError("Expected $key due as $rule but it is due as ${set.rule}.")
        }
        val expectedTarget = target ?: stream.ref
        if (set.target != expectedTarget) {
            throw AssertionError("Expected $key for $expectedTarget but it is for ${set.target}.")
        }
    }

    /** The command cancelled the schedule [key] (decision D198): it committed `wow.ScheduleCancelled`. */
    fun expectScheduleCancelled(key: String) {
        val schedules = scheduleEvents()
        if (ScheduleCancelled(key) !in schedules) {
            throw AssertionError("Expected the schedule $key cancelled but got $schedules.")
        }
    }

    /** The events the command committed beside its schedules. */
    private fun decided(): List<Any> =
        committed().stream.events.filter { it.name !in Schedules.EVENTS }.map { codec.decode(model, it) }

    /** The schedule events the command committed. */
    private fun scheduleEvents(): List<Any> =
        committed().stream.events.filter { it.name in Schedules.EVENTS }.map { codec.decode(model, it) }

    /** The committed stream leaves the aggregate in [scope]. */
    fun expectScope(scope: Scope) {
        val committed = committed().stream.scope
        if (committed != scope) {
            throw AssertionError("Expected scope $scope but got $committed.")
        }
    }

    /**
     * Checks the state after the committed events; after a command that committed nothing (it changed nothing, was
     * rejected, or its decide function threw), the state the aggregate is in.
     */
    fun expectState(check: S.() -> Unit) {
        val committed = result.getOrNull() as? CommandOutcome.Committed

        @Suppress("UNCHECKED_CAST")
        val state = if (committed != null) committed.state as S else stateAt(version)
        state.check()
    }

    /**
     * The aggregate's tags are exactly [tags] after the committed events, or, after a command that committed nothing,
     * the tags the aggregate has: those `ApplyTags` applied, with the keys its state derives (`@Tags`, decision D220)
     * overriding them, the tags query policies filter by.
     */
    fun expectTags(tags: Map<String, List<String>>) {
        val committed = result.getOrNull() as? CommandOutcome.Committed
        val actual = committed?.tags ?: tagsAt(version)
        if (actual != tags) {
            throw AssertionError("Expected tags $tags but got $actual.")
        }
    }

    /**
     * Fails with what the decide function threw when no [expectError] checked it, and on a rejection that neither
     * [expectRejected] checked nor the scenario read from [outcome].
     */
    internal fun verify() {
        val thrown = error
        if (thrown != null && !errorChecked) {
            throw thrown
        }
        val rejected = result.getOrNull() as? CommandOutcome.Rejected
        if (rejected != null && !outcomeChecked) {
            throw AssertionError(
                "The command was rejected with ${rejected.rejection}, which the scenario does not check: " +
                    "expectRejected checks a rejection.",
            )
        }
    }

    private fun committed(): CommandOutcome.Committed {
        val outcome = decidedOutcome
        return outcome as? CommandOutcome.Committed
            ?: throw AssertionError("Expected committed events but got $outcome.")
    }
}
