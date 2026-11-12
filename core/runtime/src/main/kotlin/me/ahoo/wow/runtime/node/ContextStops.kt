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

package me.ahoo.wow.runtime.node

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope

/*
 * How a node stops what it holds of each of its contexts: each context's stop runs to its end whatever another one's
 * does, and the first failure is thrown once all ended, the others suppressed in it, as the node's stop steps go on
 * past a failed one. A cancellation of the caller is no failure: it cancels them all and is thrown as it is. A
 * cancellation one stop throws while the caller is still active, such as a cancelled start it awaited or a timeout that
 * escaped it, is that stop's failure, kept as any other.
 */

/** Runs [stops] at once, each to its end; then throws the first failure, the others suppressed. */
internal suspend fun stopEach(stops: List<suspend () -> Unit>) {
    val failures = supervisorScope {
        stops.map { stop -> async { failureOf(stop) } }.awaitAll()
    }
    throwFirst(failures)
}

/** Runs [parts] in turn, each whatever the ones before did; then throws the first failure, the others suppressed. */
internal suspend fun stopInTurn(vararg parts: suspend () -> Unit) = throwFirst(parts.map { failureOf(it) })

@Suppress("TooGenericExceptionCaught") // Any failure of one stop is kept for after the others, never lost.
private suspend fun failureOf(stop: suspend () -> Unit): Exception? = try {
    stop()
    null
} catch (cancelled: CancellationException) {
    // Thrown again when the caller is cancelled; otherwise the stop's own failure.
    currentCoroutineContext().ensureActive()
    cancelled
} catch (failure: Exception) {
    failure
}

private fun throwFirst(failures: List<Exception?>) {
    val thrown = failures.filterNotNull()
    val first = thrown.firstOrNull() ?: return
    thrown.drop(1).forEach(first::addSuppressed)
    throw first
}
