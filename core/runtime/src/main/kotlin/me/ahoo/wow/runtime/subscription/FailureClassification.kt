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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.kernel.UnreadableEventException
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.runtime.CommandBusyException
import me.ahoo.wow.spi.StorageBusyException
import me.ahoo.wow.spi.StorageRefusedException
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.FailureClassifier
import me.ahoo.wow.spi.transport.TransportRefusedException
import me.ahoo.wow.spi.transport.TransportUnavailableException
import java.net.SocketException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLTransientConnectionException
import kotlin.time.Duration

/**
 * How the subscriptions of a runtime classify their handlers' failures: a handler's own
 * `@Retry(recoverable, unrecoverable)` ([HandlerRetry.classify]) first, of the failure itself or, for an
 * [UnreadableEventException], of what the kernel could not read; then, for the failure and each of its causes
 * in turn, each of [classifiers] in order, the first class one gives deciding; [FailureClass.RECOVERABLE] when none
 * does. The backends classify nothing: they throw the SPI's typed failures, which the runtime's own classifier
 * ([BuiltInFailures]) knows.
 */
@InternalWowApi
class FailureClassification(private val classifiers: List<FailureClassifier>) {
    /** The class of [failure] of a handler whose budget is [retry]. */
    fun classify(failure: Throwable, retry: HandlerRetry?): FailureClass {
        // An event the kernel could not read classifies as what it could not read (its cause).
        val subject = (failure as? UnreadableEventException)?.cause ?: failure
        retry?.classify(subject)?.let { return it }
        causes(failure).forEach { cause ->
            classifiers.forEach { classifier -> classifier.classify(cause)?.let { return it } }
        }
        return FailureClass.RECOVERABLE
    }

    @InternalWowApi
    companion object {
        /** The causes a classification walks at most: the failure, its cause, and so on. */
        private const val MAX_CAUSES = 16

        /** The runtime's own classifier alone ([BuiltInFailures]). */
        val DEFAULT: FailureClassification = FailureClassification(listOf(BuiltInFailures))

        /** The [application]'s classifiers, then the runtime's own. */
        fun of(application: List<FailureClassifier> = emptyList()): FailureClassification =
            if (application.isEmpty()) DEFAULT else FailureClassification(application + DEFAULT.classifiers)

        /**
         * The wait [failure] asks for before a retry, the server's `Retry-After` ([WowProblemException.retryAfter]),
         * from it or the first of its causes that gives one; null when none does.
         */
        fun retryAfterOf(failure: Throwable): Duration? =
            causes(failure).firstNotNullOfOrNull { (it as? WowProblemException)?.retryAfter }

        private fun causes(failure: Throwable): Sequence<Throwable> {
            val seen = HashSet<Throwable>()
            return generateSequence(failure) { it.cause }.takeWhile { seen.add(it) }.take(MAX_CAUSES)
        }
    }
}

/**
 * The failures the runtime classifies itself, whatever the backends:
 *
 * - [FailureClass.SYSTEMIC], a downstream that is down: a store a port could not reach ([StorageUnavailableException],
 *   or a command or call answered `wow.storage.unavailable`), a peer a client reached no node of
 *   (`wow.peer.unavailable`), a transport it could not reach
 *   ([TransportUnavailableException]), a socket that could not connect or was reset
 *   ([SocketException]: refused, no route, reset, broken pipe), a host name that did not resolve, an HTTP connection
 *   that timed out, and a JDBC connection that failed ([SQLTransientConnectionException],
 *   [SQLNonTransientConnectionException], SQL state class `08`).
 * - [FailureClass.BACKPRESSURE], a downstream that is up but overloaded: a store that did not admit the call
 *   ([StorageBusyException], a query store's bulkhead or a server at its capacity; checked before the
 *   [StorageUnavailableException] it is a kind of), a node that refused a command as busy ([CommandBusyException]), and a command or call answered `wow.command.busy`, `wow.wait.timeout` or
 *   `wow.query.busy`, or 429 with a `Retry-After` ([WowProblemException]).
 *
 * - [FailureClass.UNRECOVERABLE], a value a store or a transport refused for good ([StorageRefusedException],
 *   [TransportRefusedException]): writing it again fails the same way, so the aggregate is parked at once.
 *
 * Nothing else is unrecoverable: an entry this version cannot decode, say, is retried and parked, and its parked
 * retries handle it once a version that can runs. A store's timeout ([me.ahoo.wow.spi.StorageTimeoutException]) is the
 * aggregate's own, retried within its budget.
 */
internal object BuiltInFailures : FailureClassifier {
    private const val CONNECTION_EXCEPTION_CLASS = "08"
    private const val TOO_MANY_REQUESTS = 429

    /** The codes of a downstream that asks to be called later. */
    val BACKPRESSURE_CODES: Set<String> =
        setOf(FrameworkErrors.COMMAND_BUSY, FrameworkErrors.WAIT_TIMEOUT, FrameworkErrors.QUERY_BUSY)

    override fun classify(failure: Throwable): FailureClass? = when (failure) {
        is CommandBusyException, is StorageBusyException -> FailureClass.BACKPRESSURE
        is WowProblemException -> when {
            failure.code in BACKPRESSURE_CODES -> FailureClass.BACKPRESSURE
            failure.status == TOO_MANY_REQUESTS && failure.retryAfter != null -> FailureClass.BACKPRESSURE
            failure.code == FrameworkErrors.STORAGE_UNAVAILABLE -> FailureClass.SYSTEMIC
            failure.code == FrameworkErrors.PEER_UNAVAILABLE -> FailureClass.SYSTEMIC
            else -> null
        }
        is StorageRefusedException, is TransportRefusedException -> FailureClass.UNRECOVERABLE
        is StorageUnavailableException,
        is TransportUnavailableException,
        is SocketException,
        is UnknownHostException,
        is HttpConnectTimeoutException,
        is SQLTransientConnectionException,
        is SQLNonTransientConnectionException,
        -> FailureClass.SYSTEMIC
        is SQLException -> FailureClass.SYSTEMIC.takeIf {
            failure.sqlState?.startsWith(CONNECTION_EXCEPTION_CLASS) == true
        }
        else -> null
    }
}
