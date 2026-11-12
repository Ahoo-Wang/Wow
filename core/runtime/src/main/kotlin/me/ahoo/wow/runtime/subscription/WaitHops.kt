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

package me.ahoo.wow.runtime.subscription

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.kernel.HandlingRejectedException
import me.ahoo.wow.runtime.wait.ResultCodes
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.runtime.wait.handledResult
import java.time.Clock

/**
 * The waits of a [SubscriptionWorker]'s entries whose next hop is its [subscription]: whether an entry carries one
 * ([waitsHere]), and the signal the worker sends through [signals] once the entry is done with ([signal]). A side path:
 * a signal never fails the entry nor holds the checkpoint.
 */
internal class WaitHops(
    private val subscription: Subscription,
    private val signals: SignalSender,
    private val clock: Clock,
) {
    /** Whether a wait on [entry] waits for this subscription next. */
    fun waitsHere(entry: SourcedEntry): Boolean {
        val hop = entry.transport?.wait?.nextHandled ?: return false
        return hop.subscription in subscription.hopsOf(entry.stream.ref.type) &&
            (hop.context ?: subscription.context) == subscription.context
    }

    /**
     * Signals the wait on [entry], if it waits for this subscription next and is not over: SUCCEEDED with the
     * commands [handled] derived, REJECTED with the rejection it met, or FAILED when it failed or the aggregate is
     * [parked]. A FAILED signal carries the code and where the aggregate parked, never the handler's exception: its
     * class and message, which may name a database, an address or a third party's answer, go to the ledger and the
     * logs only.
     */
    @Suppress("TooGenericExceptionCaught") // A signal is best effort: whatever fails, the entry is done with.
    suspend fun signal(entry: SourcedEntry, handled: Result<HandledEntry>, parked: Boolean = false) {
        if (!waitsHere(entry)) {
            return
        }
        val wait = checkNotNull(entry.transport?.wait)
        val now = clock.millis()
        if (wait.expiredAt(now)) {
            return
        }
        val failure = handled.exceptionOrNull()
        val rejection = (failure as? HandlingRejectedException)?.error ?: handled.getOrNull()?.rejection
        val error = when {
            rejection != null -> rejection
            failure != null || parked -> {
                // The handler's exception stays in the ledger and the logs; the caller learns only where.
                failure?.let { logger.debug(it) { "Wait ${wait.waitId} fails: ${subscription.name} parked." } }
                ErrorInfo(
                    ResultCodes.SUBSCRIPTION_PARKED,
                    "${entry.stream.ref}@${entry.stream.version} is parked by ${subscription.name}; the failure " +
                        "ledger has the cause.",
                )
            }
            else -> null
        }
        val hop = checkNotNull(wait.nextHandled)
        val result = handledResult(
            entry.stream,
            hop,
            subscription.function,
            now,
            handled.getOrNull()?.derived.orEmpty(),
            error,
        ).let { if (rejection != null) it.copyWith(status = CommandResult.Status.REJECTED) else it }
        try {
            signals.send(wait.origin, WaitSignal(waitId = wait.waitId, result = result), wait.deadline)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn(failure) { "Failed to signal wait ${wait.waitId} from ${subscription.name}." }
        }
    }
}

/** The worker's: its signals are logged as they were. */
private val logger = KotlinLogging.logger(SubscriptionWorker::class.java.name)
