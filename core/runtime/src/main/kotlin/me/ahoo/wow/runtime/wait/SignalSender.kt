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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.spi.wait.Origin

/**
 * Delivers a wait signal to the node a wait started on (design 04 §6.3, decision D65).
 *
 * Signals are an observation, not part of correctness: a lost one costs the waiter a timeout, never a fact. So a
 * sender is best effort and must not hold up its caller (a subscription that has just handled an entry, or the node
 * that executed a derived command): a sender that crosses the network queues and returns, and drops what it cannot
 * deliver instead of retrying.
 */
fun interface SignalSender {
    suspend fun send(origin: Origin, signal: WaitSignal)

    companion object {
        /** Sends nothing: no one waits on this node's signals. */
        val NONE: SignalSender = SignalSender { _, _ -> }
    }
}

/**
 * The in-process [SignalSender]: a signal whose origin is this [node] goes straight to its [registry]; any other goes
 * to [remote], the sender to other nodes' internal signal entries (roadmap 4.2 and 6.6), which drops it by default.
 */
class LocalSignalSender(
    private val node: String,
    private val registry: WaitRegistry,
    private val remote: SignalSender = SignalSender { origin, signal ->
        logger.debug { "No sender to ${origin.node}; dropping the signal of wait ${signal.waitId}." }
    },
) : SignalSender {
    override suspend fun send(origin: Origin, signal: WaitSignal) {
        if (origin.node == node) {
            registry.signal(signal)
        } else {
            remote.send(origin, signal)
        }
    }

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}
