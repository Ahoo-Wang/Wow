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
import me.ahoo.wow.api.schedule.CancelSchedule
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.SetSchedule
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.spi.json.WowJson
import kotlin.reflect.KClass

/**
 * The checks of one handling by a [ProcessorSpec]: the handlers that were called ([handled]), the commands they
 * derived ([commands]), among them the schedules they set or cancelled ([schedules]), or what one of them threw
 * ([expectError]).
 */
@Suppress("TooManyFunctions") // The DSL's expectations.
class ProcessorExpectations internal constructor(
    /** The handlers called, by subscription name (`processor.function`), in order. */
    val handled: List<String>,
    private val result: Result<ProcessorSpec.Handled>,
    private val again: () -> Result<ProcessorSpec.Handled>,
    private val forker: ((String, ProcessorSpec.() -> Unit) -> Unit)? = null,
) {
    private var errorChecked = false

    private val done: ProcessorSpec.Handled
        get() = result.getOrElse { throw AssertionError("Expected the handling to end but it threw $it.", it) }

    /** The commands the handlers derived, in order, but the schedules they set or cancelled ([schedules]). */
    val commands: List<Command<*>> get() = done.commands.filterNot { it.isSchedule() }

    /**
     * The schedules the handlers set or cancelled (decision D198): their derived system commands `wow.schedule.Set`
     * and `wow.schedule.Cancel`, in order.
     */
    val schedules: List<Command<*>> get() = done.commands.filter { it.isSchedule() }

    private fun Command<*>.isSchedule(): Boolean = body is SetSchedule || body is CancelSchedule

    /**
     * The commands the handlers called synchronously (decision D193), in order, each answered by
     * [ProcessorSpec.answerCalls]; those of a handling that threw are not listed.
     */
    val calls: List<Command<*>> get() = done.calls

    /** The handlers called commands of exactly these [bodies] synchronously, in order. */
    fun expectCalls(vararg bodies: Any) {
        val called = calls.map { it.body }
        if (called != bodies.toList()) {
            throw AssertionError("Expected the calls ${bodies.toList()} but got $called.")
        }
    }

    /**
     * Runs [block] on a copy of the specification as this handling left it, as [ProcessorSpec.fork] does (decision
     * D245): the scenarios after a handling branch from its checks, a rejection of the command it derived in one
     * fork and its receipt in another, say. A check that fails before it ends the block, so the forks after it do not
     * run.
     */
    fun fork(name: String = "", block: ProcessorSpec.() -> Unit) {
        val forker = checkNotNull(forker) { "These expectations come from a time step: fork the specification instead." }
        forker(name, block)
    }

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

    /** The handlers derived commands of exactly these [bodies], in order. */
    fun expectCommands(vararg bodies: Any) {
        val derived = commands.map { it.body }
        if (derived != bodies.toList()) {
            throw AssertionError("Expected the commands ${bodies.toList()} but got $derived.")
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

    /** The handlers derived exactly one command, of [body], to [to] when given: that command. */
    @Suppress("ThrowsCount")
    fun expectCommand(body: Any, to: AggregateRef? = null): Command<*> {
        val command = commands.singleOrNull() ?: throw AssertionError("Expected one command but got ${describe(commands)}.")
        if (command.body != body) {
            throw AssertionError("Expected the command $body but got ${command.body}.")
        }
        if (to != null && command.ref != to) {
            throw AssertionError("Expected ${command.body} to $to but it goes to ${command.ref}.")
        }
        return command
    }

    /** The handlers derived exactly one command, a [C], which [check] checks: that command. */
    inline fun <reified C : Any> expectCommand(noinline check: Command<C>.() -> Unit = {}): Command<C> =
        expectCommand(C::class, check)

    /** The handlers derived exactly one command, of [type], which [check] checks: that command. */
    fun <C : Any> expectCommand(type: KClass<C>, check: Command<C>.() -> Unit = {}): Command<C> {
        val command = commands.singleOrNull() ?: throw AssertionError("Expected one command but got ${describe(commands)}.")
        if (!type.isInstance(command.body)) {
            throw AssertionError("Expected a ${type.simpleName} but got ${command.body}.")
        }
        @Suppress("UNCHECKED_CAST")
        return (command as Command<C>).apply(check)
    }

    /**
     * The handlers scheduled [command] under [key] (decision D198): they derived `wow.schedule.Set` to [target] when
     * given, which schedules [command] as [rule] says when it is given.
     */
    @Suppress("ThrowsCount")
    fun expectScheduled(key: String, command: Any, rule: ScheduleRule? = null, target: AggregateRef? = null) {
        val set = schedules.firstOrNull { (it.body as? SetSchedule)?.key == key && (target == null || it.ref == target) }
            ?: throw AssertionError("Expected a schedule $key but got ${describe(schedules)}.")
        val body = set.body as SetSchedule
        val scheduled = CODEC.decodeTree(WowJson.mapper.readTree(body.command), command.javaClass)
        if (scheduled != command) {
            throw AssertionError("Expected $key to send $command but it sends $scheduled.")
        }
        if (rule != null && body.rule != rule) {
            throw AssertionError("Expected $key due as $rule but it is due as ${body.rule}.")
        }
    }

    /** The handlers cancelled the schedule [key], of [target] when given (decision D198). */
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
     * Handling the same commit, or rejection, again, with a fresh context as the runtime gives a retry, derives the
     * same commands (ids and request ids included), the schedules among them, and calls the same ones: the targets
     * decide them once.
     */
    @Suppress("ThrowsCount")
    fun expectDeterministic() {
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

    private fun describe(commands: List<Command<*>>): String =
        commands.joinToString(prefix = "[", postfix = "]") { "${it.body} to ${it.ref}" }

    private companion object {
        val CODEC = EventCodec()
    }
}
