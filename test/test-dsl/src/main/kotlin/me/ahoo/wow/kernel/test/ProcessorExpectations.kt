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

package me.ahoo.wow.kernel.test

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.SagaSchedule
import java.time.Instant
import kotlin.reflect.KClass
import kotlin.time.Duration

/**
 * The checks of one handling by a [ProcessorSpec]: the handlers that were called ([handled]), the commands they
 * derived ([commands]) and the schedules they changed ([schedules]), or what one of them threw ([expectError]).
 */
@Suppress("TooManyFunctions") // The DSL's expectations.
class ProcessorExpectations internal constructor(
    /** The handlers called, by subscription name (`processor.function`), in order. */
    val handled: List<String>,
    /** The commit time of the commit handled (epoch milliseconds), which a schedule's `after` counts from. */
    private val commitTime: Long?,
    private val result: Result<ProcessorSpec.Handled>,
    private val again: () -> Result<ProcessorSpec.Handled>,
) {
    private var errorChecked = false

    private val done: ProcessorSpec.Handled
        get() = result.getOrElse { throw AssertionError("Expected the handling to end but it threw $it.", it) }

    /** The commands the handlers derived, in order. */
    val commands: List<Command<*>> get() = done.commands

    /** The schedules the handlers changed (decision D166), the last change of each target and key. */
    val schedules: List<SagaSchedule> get() = done.schedules

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
     * The handlers scheduled [command] under [key] (decision D166), for [target] when given, due [at] or [after] the
     * commit when either is given.
     */
    @Suppress("ThrowsCount")
    fun expectScheduled(
        key: String,
        command: Any,
        at: Instant? = null,
        after: Duration? = null,
        target: AggregateRef? = null,
    ) {
        val put = schedules.filterIsInstance<SagaSchedule.Put>()
            .firstOrNull { it.key == key && (target == null || it.target == target) }
            ?: throw AssertionError("Expected a schedule $key but got $schedules.")
        if (put.command != command) {
            throw AssertionError("Expected $key to send $command but it sends ${put.command}.")
        }
        val due = at?.toEpochMilli() ?: after?.let {
            requireNotNull(commitTime) { "A rejection's handler schedules nothing." } + it.inWholeMilliseconds
        }
        if (due != null && put.dueAt != due) {
            throw AssertionError(
                "Expected $key due at ${Instant.ofEpochMilli(due)} " +
                    "but it is due at ${Instant.ofEpochMilli(put.dueAt)}.",
            )
        }
    }

    /** The handlers cancelled the schedule [key], of [target] when given (decision D166). */
    fun expectScheduleCancelled(key: String, target: AggregateRef? = null) {
        val cancelled = schedules.filterIsInstance<SagaSchedule.Cancel>()
            .any { it.key == key && (target == null || it.target == target) }
        if (!cancelled) {
            throw AssertionError("Expected the schedule $key cancelled but got $schedules.")
        }
    }

    /** The handlers changed no schedule. */
    fun expectNoSchedule() {
        if (schedules.isNotEmpty()) throw AssertionError("Expected no schedule but got $schedules.")
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
     * same commands (ids and request ids included) and the same schedules: the targets decide them once.
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
        if (second.schedules != first.schedules) {
            throw AssertionError("Handling it again scheduled ${second.schedules}, not ${first.schedules}.")
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
}
