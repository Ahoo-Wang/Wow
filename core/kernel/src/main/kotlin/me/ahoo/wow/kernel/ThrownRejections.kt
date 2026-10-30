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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.decide.RejectedException
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.api.result.FrameworkCodes
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Which exceptions a decide function throws are refusals of the command rather than failures of its handling,
 * each answered as the rejection it stands for, exactly as a returned one:
 *
 * - a [RejectedException] ([me.ahoo.wow.api.decide.DecideScope.reject]): its rejection;
 * - a [CommandRejectedException] of a Wow client call to another context: a rejection with the target's code;
 * - an [IllegalArgumentException] itself (`require`, `requireNotNull`): [FrameworkCodes.COMMAND_INVALID];
 * - an [IllegalStateException] itself (`check`, `checkNotNull`, `error`): [FrameworkCodes.COMMAND_REFUSED].
 *
 * The last two match the class exactly: a subclass (`CancellationException`, `NumberFormatException`, a library's own
 * exception, a Wow API's [me.ahoo.wow.api.decide.Misuse]) is not a refusal, and neither is anything else, a remote call's `IOException` or timeout included: those
 * stay failures of the command's handling. A [CancellationException] is never one.
 */
internal object ThrownRejections {
    /** The rejection [thrown] stands for, or null when it is a failure. */
    fun rejectionOf(thrown: Throwable): Rejection? {
        if (thrown is CancellationException) return null
        val rejection = when {
            thrown is RejectedException -> thrown.rejection
            thrown is CommandRejectedException -> thrown.result.error?.let {
                Rejection(it.code, it.message, it.extensions)
            }
            thrown.javaClass == IllegalArgumentException::class.java ->
                Rejection(FrameworkCodes.COMMAND_INVALID, thrown.message.orEmpty())
            thrown.javaClass == IllegalStateException::class.java ->
                Rejection(FrameworkCodes.COMMAND_REFUSED, thrown.message.orEmpty())
            else -> null
        }
        if (rejection != null) {
            logger.debug(thrown) { "The decide function refused the command by throwing: ${rejection.code}." }
        }
        return rejection
    }

    /** The codes a domain command's thrown `require` and `check` are answered with, which it may not reject with itself. */
    val CODES: Set<String> = setOf(FrameworkCodes.COMMAND_INVALID, FrameworkCodes.COMMAND_REFUSED)
}
