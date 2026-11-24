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

package me.ahoo.wow.kernel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.RejectedException
import me.ahoo.wow.api.lifecycle.Lifecycle
import kotlin.coroutines.CoroutineContext

/**
 * How the [Kernel] calls the code of a decision ([decideAll]): the command's decide function, then its after hooks,
 * each checked against the events it declares. One marked `@Blocking` ([DecideFunction.Suspending.blocking]) runs on
 * [blocking], by default the node's virtual threads ([BlockingDispatcher]); every other one runs inline in the
 * caller's coroutine.
 *
 * A function that ends with a cancellation of its own while its caller still waits, a call it bounded with
 * `withTimeout` that did not answer in time, fails the command ([CancelledDecisionException]); only the caller's own
 * cancellation goes through as a cancellation.
 */
internal class Decisions(private val blocking: CoroutineContext) {
    /** The decision of [decider] on [state], its events followed by those of [model]'s after hooks. */
    internal suspend fun <S : Any> decideAll(
        model: AggregateModel<S>,
        decider: DecideFunction<S>,
        scope: DecideScope,
        state: S,
        body: Any,
    ): Decision {
        val decision = decide(decider, scope, state, body)
        val decided = when (decision) {
            is Decision.Events -> decision.events
            Decision.NoOp -> emptyList()
            is Decision.Rejected -> return decision.also { rejectedWith(model, body, it.events, decider.produces) }
        }
        declared(model, body, decided, decider.produces, "decide function")
        val afters = model.afterFunctions(body.javaClass)
        if (afters.isEmpty()) {
            return decision
        }
        val domain = decided + afters.flatMap { hook ->
            after(hook, scope, state, body).also { declared(model, body, it, hook.produces, "after hook") }
        }
        return if (domain.isEmpty()) Decision.NoOp else Decision.Events(domain)
    }

    /**
     * [events], which a [function] of [model] produced for [command]; an [UndeclaredEventException] when one is not of
     * [produces], the events it declares. Not checked when they are unknown.
     */
    private fun declared(
        model: AggregateModel<*>,
        command: Any,
        events: List<Any>,
        produces: Set<Class<*>>?,
        function: String,
    ) {
        if (produces == null) return
        val undeclared = events.firstOrNull { it.javaClass !in produces } ?: return
        val commandClass = command.javaClass
        throw UndeclaredEventException(
            model.type,
            commandClass,
            undeclared.javaClass,
            "${model.type}'s $function of ${commandClass.simpleName} produced ${undeclared.javaClass.simpleName}, " +
                "which it does not declare: neither its return type nor its produces lists it.",
        )
    }

    /**
     * [events], those a rejection of [command] carries; an [UndeclaredEventException] when one is a lifecycle event,
     * or, when the function's [produces] is known, one it does not declare. A rejection's events record the refused
     * attempt: a lifecycle event among them would delete, restore or transfer the aggregate on a command the aggregate
     * refused, which only a decision of events may do; and the Manifest lists what a command can commit from its
     * `produces`, so an event a rejection carries is declared there as a decision's is. Replaying a log is unaffected:
     * events are sourced, never decided again.
     */
    private fun rejectedWith(model: AggregateModel<*>, command: Any, events: List<Any>, produces: Set<Class<*>>?) {
        val commandClass = command.javaClass
        events.firstOrNull { it.javaClass in LIFECYCLE_EVENTS }?.let { lifecycle ->
            throw UndeclaredEventException(
                model.type,
                commandClass,
                lifecycle.javaClass,
                "${model.type}'s decide function of ${commandClass.simpleName} rejected it with " +
                    "${lifecycle.javaClass.simpleName}: a rejection's events are the aggregate's own, never a " +
                    "lifecycle event, which only a decision of events commits.",
            )
        }
        if (produces == null) return
        val undeclared = events.firstOrNull { it.javaClass !in produces } ?: return
        val event = undeclared.javaClass.simpleName
        throw UndeclaredEventException(
            model.type,
            commandClass,
            undeclared.javaClass,
            "${model.type}'s decide function of ${commandClass.simpleName} rejected it with $event, which it does " +
                "not declare: a rejection's events are declared as a decision's are. Add it to the function's " +
                "@OnCommand(produces = [$event::class]).",
        )
    }

    /** The decision of [decider], a thrown refusal answered as a returned one ([ThrownRejections]). */
    private suspend fun <S : Any> decide(
        decider: DecideFunction<S>,
        scope: DecideScope,
        state: S,
        body: Any,
    ): Decision = try {
        when (decider) {
            is DecideFunction.Sync -> decider.decide(scope, state, body)
            is DecideFunction.Suspending -> if (decider.blocking) {
                withContext(blocking) { decider.decide(scope, state, body) }
            } else {
                decider.decide(scope, state, body)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancellationOf(cancelled, body, "decide function")
    } catch (@Suppress("TooGenericExceptionCaught") thrown: Exception) {
        ThrownRejections.rejectionOf(thrown)?.let { Decision.Rejected(it, (thrown as? RejectedException)?.events.orEmpty()) }
            ?: throw thrown
    }

    private suspend fun <S : Any> after(
        after: AfterFunction<S>,
        scope: DecideScope,
        state: S,
        body: Any,
    ): List<Any> = try {
        when (after) {
            is AfterFunction.Sync -> after.after(scope, state, body)
            is AfterFunction.Suspending -> if (after.blocking) {
                withContext(blocking) { after.after(scope, state, body) }
            } else {
                after.after(scope, state, body)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancellationOf(cancelled, body, "after hook")
    }

    /**
     * What [cancelled], ending the [function] of the command [body], is: the caller's cancellation, thrown on as it is,
     * when this coroutine is cancelled; else one of the function's own, a call it bounded with `withTimeout`, which is a
     * failure of the command ([CancelledDecisionException]), not the caller's cancellation.
     */
    private suspend fun cancellationOf(cancelled: CancellationException, body: Any, function: String): Exception =
        if (currentCoroutineContext().isActive) {
            CancelledDecisionException(body.javaClass, function, cancelled)
        } else {
            cancelled
        }

    private companion object {
        val LIFECYCLE_EVENTS: Set<Class<*>> = Lifecycle.EVENTS.values.toSet()
    }
}
