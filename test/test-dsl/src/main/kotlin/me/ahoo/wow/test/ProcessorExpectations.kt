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
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.schedule.CancelSchedule
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.SetSchedule
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.FailureClassification
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.kernel.HandlingRejectedException
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.subscription.FailureClass
import kotlin.reflect.KClass

/**
 * The checks of one handling by a [ProcessorSpec]: the handlers that were called ([handled]), the commands they
 * derived ([commands]), among them the schedules they set or cancelled ([schedules]), or what one of them threw
 * ([expectError]). The steps that follow a saga's handling go on from its checks: the rejection ([whenRejected]) or
 * the receipt ([whenCommitted]) of a command it derived, in place or in a [fork].
 */
@WowTestDsl
@Suppress("TooManyFunctions") // The DSL's expectations.
class ProcessorExpectations internal constructor(
    /** The handlers called, by subscription name (`processor.function`), in order. */
    val handled: List<String>,
    private val result: Result<ProcessorSpec.Handled>,
    private val again: () -> Result<ProcessorSpec.Handled>,
    /** The specification that handled it, which the steps after it go on with. */
    private val spec: ProcessorSpec,
    /** The retry budget of the handler that threw, its `@Retry`; null when it gives none or none threw. */
    private val retry: HandlerRetry? = null,
) {
    private var errorChecked = false

    private val done: ProcessorSpec.Handled
        get() = result.getOrElse { throw AssertionError("Expected the handling to end but it threw $it.", it) }

    /** The commands the handlers derived, in order, but the schedules they set or cancelled ([schedules]). */
    val commands: List<Command<*>> get() = done.commands.filterNot { it.isSchedule() }

    /**
     * The schedules the handlers set or cancelled: their derived system commands `wow.schedule.Set`
     * and `wow.schedule.Cancel`, in order.
     */
    val schedules: List<Command<*>> get() = done.commands.filter { it.isSchedule() }

    private fun Command<*>.isSchedule(): Boolean = body is SetSchedule || body is CancelSchedule

    /**
     * The commands the handlers called synchronously, in order, each answered by
     * [ProcessorSpec.answerCalls]; those of a handling that threw are not listed.
     */
    val calls: List<Command<*>> get() = done.calls

    /**
     * The handlers called commands of exactly these [bodies] synchronously, in order, field by field as Wow writes
     * them to JSON: a mismatch lists each difference by its path.
     */
    fun expectCalls(vararg bodies: Any) {
        val differences = differences(bodies.map { null to it }, calls)
        if (differences.isNotEmpty()) {
            throw AssertionError("The calls differ:${StructuralDiff.lines(differences)}")
        }
    }

    /**
     * Runs [block] on a copy of the specification as this handling left it, as [ProcessorSpec.fork] does:
     * the scenarios after a handling branch from its checks, a rejection of the command it derived in one
     * fork and its receipt in another, say. A check that fails before it ends the block, so the forks after it do not
     * run.
     */
    fun fork(name: String = "", block: ProcessorSpec.() -> Unit) = spec.fork(name, block)

    /**
     * Hands the rejection of [command], one this handling derived, to the saga's `@OnRejected` handler of it, as
     * [ProcessorSpec.whenRejected] does: the specification goes on from this handling.
     */
    fun whenRejected(
        command: Command<*>,
        code: String,
        message: String = code,
        commandType: String = commandTypeOf(command),
        expect: ProcessorExpectations.() -> Unit = {},
    ) = spec.whenRejected(command, code, message, commandType, expect)

    /**
     * Hands the success receipt of [command], one this handling derived, to the saga's `@OnCommitted` handler of it,
     * as [ProcessorSpec.whenCommitted] does: the specification goes on from this handling.
     */
    @Suppress("LongParameterList")
    fun whenCommitted(
        command: Command<*>,
        version: Int = 1,
        aggregateId: String = command.ref.id,
        duplicate: Boolean = false,
        commandType: String = commandTypeOf(command),
        expect: ProcessorExpectations.() -> Unit = {},
    ) = spec.whenCommitted(command, version, aggregateId, duplicate, commandType, expect)

    /** The handlers called no command synchronously. */
    fun expectNoCall() {
        if (calls.isNotEmpty()) throw AssertionError("Expected no call but got ${describe(calls)}.")
    }

    /** What a handler threw; null when they all returned. */
    val error: Throwable? get() = result.exceptionOrNull()

    /** The handlers called are exactly [functions], by function name or subscription name, in order. */
    fun expectHandledBy(vararg functions: String) {
        val names = handled.map { it.substringAfter('.') }
        if (functions.toList() != names && functions.toList() != handled) {
            throw AssertionError("Expected the handlers ${functions.toList()} but $handled handled it.")
        }
    }

    /** The handlers derived no command. */
    fun expectNoCommand() {
        if (commands.isNotEmpty()) throw AssertionError("Expected no command but got ${describe(commands)}.")
    }

    /** The handlers derived exactly [count] commands, as an aggregate's `expectEventCount` counts its events. */
    fun expectCommandCount(count: Int) {
        if (commands.size != count) {
            throw AssertionError("Expected $count commands but got ${commands.size}: ${describe(commands)}.")
        }
    }

    /**
     * The handlers derived commands of exactly these [bodies], in order, field by field as Wow writes them to JSON: a
     * mismatch lists each difference by its path, `[0].quantity: expected 3, was 2`.
     */
    fun expectCommands(vararg bodies: Any) = expectCommands(bodies.map { null to it })

    /**
     * The handlers derived exactly these [commands], each a body to the aggregate it goes to (`stock to
     * ReserveStock(…)`), in order, as [expectCommands] of bodies compares them.
     */
    fun expectCommands(vararg commands: Pair<AggregateRef, Any>) = expectCommands(commands.toList())

    private fun expectCommands(expected: List<Pair<AggregateRef?, Any>>) {
        val differences = differences(expected, commands)
        if (differences.isNotEmpty()) {
            throw AssertionError("The commands differ:${StructuralDiff.lines(differences)}")
        }
    }

    /** The handlers derived commands of exactly these types, in order. */
    fun expectCommandTypes(vararg types: KClass<*>) {
        val derived = commands.map { it.body.javaClass.kotlin }
        if (derived != types.toList()) {
            throw AssertionError(
                "Expected the command types ${types.map { it.simpleName }} but got ${derived.map { it.simpleName }}.",
            )
        }
    }

    /**
     * The handlers derived exactly one command, of [body], to [to] when given, as [expectCommands] compares them: that
     * command.
     */
    fun expectCommand(body: Any, to: AggregateRef? = null): Command<*> {
        val command = commands.singleOrNull() ?: throw AssertionError("Expected one command but got ${describe(commands)}.")
        val differences = differences(listOf(to to body), listOf(command))
        if (differences.isNotEmpty()) {
            throw AssertionError("The command differs:${StructuralDiff.lines(differences)}")
        }
        return command
    }

    /**
     * The handlers derived exactly one command, a [C], whose body [check] checks, as an aggregate's
     * [Expectations.expectEvent] checks its event: that body.
     */
    inline fun <reified C : Any> expectCommand(noinline check: C.() -> Unit = {}): C = expectCommand(C::class, check)

    /** The handlers derived exactly one command, of [type], whose body [check] checks: that body. */
    fun <C : Any> expectCommand(type: KClass<C>, check: C.() -> Unit = {}): C =
        expectCommandEnvelope(type).body.apply(check)

    /**
     * The handlers derived exactly one command, a [C], which [check] checks with its envelope: the aggregate it goes
     * to, its ids, scope, request facts and operator beside its body ([Command.body]), as an aggregate's
     * [Expectations.expectEventEnvelope] checks its event's.
     */
    inline fun <reified C : Any> expectCommandEnvelope(noinline check: Command<C>.() -> Unit = {}): Command<C> =
        expectCommandEnvelope(C::class, check)

    /** The handlers derived exactly one command, of [type], which [check] checks with its envelope. */
    fun <C : Any> expectCommandEnvelope(type: KClass<C>, check: Command<C>.() -> Unit = {}): Command<C> {
        val command = commands.singleOrNull() ?: throw AssertionError("Expected one command but got ${describe(commands)}.")
        if (!type.isInstance(command.body)) {
            throw AssertionError("Expected a ${type.simpleName} but got ${command.body}.")
        }
        @Suppress("UNCHECKED_CAST")
        return (command as Command<C>).apply(check)
    }

    /**
     * The handlers scheduled [command] under [key]: they derived `wow.schedule.Set` to [target] when
     * given, which schedules [command] as [rule] says when it is given.
     */
    fun expectScheduled(key: String, command: Any, rule: ScheduleRule? = null, target: AggregateRef? = null) {
        val set = schedules.firstOrNull { (it.body as? SetSchedule)?.key == key && (target == null || it.ref == target) }
            ?: throw AssertionError("Expected a schedule $key but got ${describe(schedules)}.")
        val body = set.body as SetSchedule
        val scheduled = CODEC.decodeTree(WowJson.mapper.readTree(body.command), command.javaClass)
        val differences = StructuralDiff.of(command, scheduled, "command") +
            (rule?.let { StructuralDiff.of(it, body.rule, "rule") }.orEmpty())
        if (differences.isNotEmpty()) {
            throw AssertionError("The schedule $key differs:${StructuralDiff.lines(differences)}")
        }
    }

    /** The handlers cancelled the schedule [key], of [target] when given. */
    fun expectScheduleCancelled(key: String, target: AggregateRef? = null) {
        val cancelled = schedules.any { it.body == CancelSchedule(key) && (target == null || it.ref == target) }
        if (!cancelled) {
            throw AssertionError("Expected the schedule $key cancelled but got ${describe(schedules)}.")
        }
    }

    /** The handlers changed no schedule. */
    fun expectNoSchedule() {
        if (schedules.isNotEmpty()) throw AssertionError("Expected no schedule but got ${describe(schedules)}.")
    }

    /** No handler threw. */
    fun expectNoError() {
        error?.let { throw AssertionError("Expected no error but the handling threw $it.", it) }
    }

    /** A handler threw a [T], which [check] checks. */
    inline fun <reified T : Throwable> expectError(noinline check: T.() -> Unit = {}): T = expectError(T::class, check)

    /** A handler threw a [type], which [check] checks. */
    fun <T : Throwable> expectError(type: KClass<T>, check: T.() -> Unit = {}): T {
        errorChecked = true
        val thrown = error ?: throw AssertionError("Expected a ${type.simpleName} but the handling ended.")
        if (!type.isInstance(thrown)) {
            throw AssertionError("Expected a ${type.simpleName} but the handling threw $thrown.", thrown)
        }
        @Suppress("UNCHECKED_CAST")
        return (thrown as T).apply(check)
    }

    /**
     * The handling failed, and the runtime retries it after this [attempt], the first by default, as [parking] reads
     * what it threw. The backoff between the attempts is the runtime's.
     */
    fun expectRetried(attempt: Int = 1): Throwable {
        val (thrown, verdict) = parking(attempt)
        if (verdict.parks) {
            throw AssertionError("Expected a retry after attempt $attempt but it parks: ${verdict.reason}.", thrown)
        }
        return thrown
    }

    /**
     * The handling failed, and the runtime parks it after this [attempt], the first by default, for its parked
     * retries and an operator, as [parking] reads what it threw.
     */
    fun expectParked(attempt: Int = 1): Throwable {
        val (thrown, verdict) = parking(attempt)
        if (!verdict.parks) {
            throw AssertionError(
                "Expected the handling parked after attempt $attempt but it is retried: ${verdict.reason}.",
                thrown,
            )
        }
        return thrown
    }

    /**
     * What a handler threw, and whether the runtime parks the handling after [attempt], deciding as its subscriptions
     * do: a rejection the handling met ([HandlingRejectedException]: a call's rejection a saga's handler let through,
     * a rejection of a derived command no `@OnRejected` handler takes) parks at once; else [FailureClassification]
     * classifies it, the handler's `@Retry` first, then the classifiers of the modules on the classpath, then the
     * built-in rules. A failure it takes for unrecoverable parks
     * at once; a downstream that is down or pushes back is retried without spending the budget; any other failure is
     * retried until the attempt spends the budget ([HandlerRetry.maxAttempts], [HandlerRetry.DEFAULT_MAX_ATTEMPTS]
     * when it gives none). An application's own classifiers are the runtime's, which its own tests check.
     */
    private fun parking(attempt: Int): Pair<Throwable, Verdict> {
        require(attempt >= 1) { "Attempts are counted from 1." }
        errorChecked = true
        val thrown = error ?: throw AssertionError("Expected the handling to fail but it ended.")
        if (thrown is HandlingRejectedException) return thrown to Verdict(true, "a rejection parks at once")
        val name = thrown.javaClass.simpleName
        val verdict = when (CLASSIFICATION.classify(thrown, retry)) {
            FailureClass.UNRECOVERABLE -> if (retry?.classify(thrown) == FailureClass.UNRECOVERABLE) {
                Verdict(true, "its @Retry takes $name for unrecoverable")
            } else {
                Verdict(true, "$name is unrecoverable")
            }
            FailureClass.SYSTEMIC, FailureClass.BACKPRESSURE ->
                Verdict(false, "$name is its downstream's, retried without spending the handler's budget")
            FailureClass.RECOVERABLE -> if (attempt >= budget()) {
                Verdict(true, "attempt $attempt spends its ${budget()} attempts")
            } else {
                Verdict(false, "$name is recoverable, and attempt $attempt of ${budget()} leaves some of its budget")
            }
        }
        return thrown to verdict
    }

    /** Whether a failed handling [parks], and the [reason]. */
    private data class Verdict(val parks: Boolean, val reason: String)

    private fun budget(): Int = retry?.maxAttempts ?: HandlerRetry.DEFAULT_MAX_ATTEMPTS

    /**
     * A saga's handling of the same commit, or rejection, again, with a fresh context as the runtime gives a retry,
     * derives the same commands (ids and request ids included), the schedules among them, and calls the same ones: the
     * targets decide them once. A projection's or a reactor's handling would run its side effects again, so it has no
     * such check.
     */
    @Suppress("ThrowsCount")
    fun expectDeterministic() {
        check(spec.kind == FunctionKind.SAGA) {
            "expectDeterministic checks a saga's commands: handling a ${spec.kind} again would repeat its side effects."
        }
        val first = done
        val second = again().getOrElse { throw AssertionError("Handling it again threw $it.", it) }
        if (second.commands != first.commands) {
            throw AssertionError(
                "Handling it again derived ${describe(second.commands)}, not ${describe(first.commands)}.",
            )
        }
        if (second.calls != first.calls) {
            throw AssertionError("Handling it again called ${describe(second.calls)}, not ${describe(first.calls)}.")
        }
    }

    /** Fails with what a handler threw when no [expectError] checked it. */
    internal fun verify() {
        val thrown = error
        if (thrown != null && !errorChecked) {
            throw thrown
        }
    }

    /**
     * The differences between the [expected] commands, each a body to the aggregate it goes to when that is given, and
     * the [actual] ones: by index, the target under `[i].to` and the body's fields under `[i]`.
     */
    private fun differences(expected: List<Pair<AggregateRef?, Any>>, actual: List<Command<*>>): List<String> =
        buildList {
            if (expected.size != actual.size) add("expected ${expected.size} commands, was ${actual.size}")
            expected.indices.intersect(actual.indices).forEach { index ->
                val (to, body) = expected[index]
                if (to != null) addAll(StructuralDiff.of(to, actual[index].ref, "[$index].to"))
                addAll(StructuralDiff.of(body, actual[index].body, "[$index]"))
            }
            actual.indices.drop(expected.size).forEach { add("[$it]: unexpected ${describe(actual[it])}") }
            expected.indices.drop(actual.size).forEach { index ->
                val (to, body) = expected[index]
                add("[$index]: missing $body${to?.let { " to $it" }.orEmpty()}")
            }
        }

    private fun describe(command: Command<*>): String = "${command.body} to ${command.ref}"

    private fun describe(commands: List<Command<*>>): String =
        commands.joinToString(prefix = "[", postfix = "]") { describe(it) }

    private companion object {
        val CODEC = EventCodec()

        /**
         * The classification the runtime's subscriptions decide a retry by, the modules' classifiers included, but an
         * application's own classifiers.
         */
        val CLASSIFICATION: FailureClassification = FailureClassification.DEFAULT
    }
}
