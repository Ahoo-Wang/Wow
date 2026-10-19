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
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.FailureClassifier
import java.net.SocketException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLTransientConnectionException
import java.util.ServiceLoader

/**
 * How the subscriptions of a runtime classify their handlers' failures (decisions D229 G-6, D243): a handler's own
 * `@Retry(recoverable, unrecoverable)` ([HandlerRetry.classify]) first; then, for the failure and each of its causes
 * in turn, each of [classifiers] in order, the first class one gives deciding; [FailureClass.RECOVERABLE] when none
 * does.
 */
@InternalWowApi
class FailureClassification(private val classifiers: List<FailureClassifier>) {
    /** The class of [failure] of a handler whose budget is [retry]. */
    fun classify(failure: Throwable, retry: HandlerRetry?): FailureClass {
        retry?.classify(failure)?.let { return it }
        causes(failure).forEach { cause ->
            classifiers.forEach { classifier -> classifier.classify(cause)?.let { return it } }
        }
        return FailureClass.RECOVERABLE
    }

    @InternalWowApi
    companion object {
        /** The causes a classification walks at most: the failure, its cause, and so on. */
        private const val MAX_CAUSES = 16

        /** The classifiers on the class path ([ServiceLoader]): the backends'. */
        val LOADED: List<FailureClassifier> by lazy {
            ServiceLoader.load(FailureClassifier::class.java, FailureClassifier::class.java.classLoader).toList()
        }

        /** The backends' classifiers ([LOADED]), then the runtime's own ([BuiltInFailures]). */
        val DEFAULT: FailureClassification by lazy { FailureClassification(LOADED + BuiltInFailures) }

        /** The [application]'s classifiers, then the backends' ([LOADED]), then the runtime's own. */
        fun of(application: List<FailureClassifier> = emptyList()): FailureClassification =
            if (application.isEmpty()) DEFAULT else FailureClassification(application + DEFAULT.classifiers)

        private fun causes(failure: Throwable): Sequence<Throwable> {
            val seen = HashSet<Throwable>()
            return generateSequence(failure) { it.cause }.takeWhile { seen.add(it) }.take(MAX_CAUSES)
        }
    }
}

/**
 * The failures the runtime classifies itself, whatever the backends, as [FailureClass.SYSTEMIC]: a store a port could
 * not reach ([StorageUnavailableException]), a socket that could not connect or was reset ([SocketException]: refused,
 * no route, reset, broken pipe), a host name that did not resolve, an HTTP connection that timed out, and a JDBC
 * connection that failed ([SQLTransientConnectionException], [SQLNonTransientConnectionException], SQL state class
 * `08`). It takes nothing for unrecoverable: an entry this version cannot decode, say, is retried and parked, and its
 * parked retries handle it once a version that can runs.
 */
internal object BuiltInFailures : FailureClassifier {
    private const val CONNECTION_EXCEPTION_CLASS = "08"

    override fun classify(failure: Throwable): FailureClass? = when (failure) {
        is StorageUnavailableException,
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
