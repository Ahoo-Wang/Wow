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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.RejectedException
import kotlin.coroutines.CoroutineContext

/**
 * How the [Kernel] calls the code of a decision ([decideAll]): the command's decide function, then its after hooks,
 * each checked against the events it declares. One marked `@Blocking` ([DecideFunction.Suspending.blocking]) runs on
 * [blocking], by default the node's virtual threads ([BlockingDispatcher]); every other one runs inline in the
 * caller's coroutine.
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
            is Decision.Rejected -> return decision
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
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") thrown: Exception) {
        ThrownRejections.rejectionOf(thrown)?.let { Decision.Rejected(it, (thrown as? RejectedException)?.events.orEmpty()) }
            ?: throw thrown
    }

    private suspend fun <S : Any> after(
        after: AfterFunction<S>,
        scope: DecideScope,
        state: S,
        body: Any,
    ): List<Any> = when (after) {
        is AfterFunction.Sync -> after.after(scope, state, body)
        is AfterFunction.Suspending -> if (after.blocking) {
            withContext(blocking) { after.after(scope, state, body) }
        } else {
            after.after(scope, state, body)
        }
    }
}
