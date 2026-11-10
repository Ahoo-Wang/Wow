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

package me.ahoo.wow.runtime.wait

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.runtime.CommandBusyException
import me.ahoo.wow.spi.wait.WaitContext

/**
 * Writes the commands admitted on a node to a command topic instead of executing them there: the topic router's
 * or a peer context's. The target's intake executes them.
 */
@InternalWowApi
interface CommandRelay {
    /** The command topic, as a failure names it. */
    val topic: String

    /** Writes [command] with [wait] and the trace it is sent in, and waits for the broker; throws when it did not ack. */
    suspend fun send(command: Command<*>, wait: WaitContext?)
}

/**
 * What executing a command reached at once: its [committed] result, at its COMMITTED hop, then the
 * later hops it [reached] without waiting for a subscription. The hops after them are signalled to the wait's origin.
 * [outcome] is the kernel's when this node executed the command; null when another node did and replied, or when the
 * executor did not take it.
 */
@InternalWowApi
data class Settlement(
    val committed: CommandResult,
    val reached: List<CommandResult> = emptyList(),
    val outcome: CommandOutcome? = null,
    /**
     * What failed the handling of a [ResultCodes.FAILED] command here, or the store that refused it
     * ([ResultCodes.STORAGE_UNAVAILABLE]): for this node's logs, ledger and failure classification only.
     */
    val cause: Throwable? = null,
) {
    /** What made [committed] FAILED, when this node knows it: the handling's [cause], or an unknown append's. */
    val failure: Throwable? get() = cause ?: (outcome as? CommandOutcome.Unknown)?.cause

    /** The rejection [committed] ends with, when it is REJECTED (a business outcome). */
    val rejection: Rejection?
        get() = committed.error?.takeIf { committed.status == CommandResult.Status.REJECTED }
            ?.let { Rejection(it.code, it.message, it.extensions) }

    /** Whether [committed] is FAILED: a technical failure, such as contention, an unknown outcome or a busy lane. */
    val failed: Boolean get() = committed.status == CommandResult.Status.FAILED
}

/**
 * The backpressure [command] met when this settlement says the node refused it as busy ([ResultCodes.BUSY], here or
 * on its partition's owner): what the node's own sources throw instead of a technical failure.
 */
internal fun Settlement.busyOf(command: Command<*>): CommandBusyException? =
    committed.error?.takeIf { failed && it.code == ResultCodes.BUSY }?.let {
        CommandBusyException(command.ref, command.id, it.message)
    }
