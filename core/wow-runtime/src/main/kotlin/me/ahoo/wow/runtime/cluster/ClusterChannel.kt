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

package me.ahoo.wow.runtime.cluster

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap

/**
 * The cluster channel (design 02 §3; decisions D24, D26): the one internal channel between the nodes of a service,
 * listening on their internal port only and trusted without authentication (D35). This port is what the runtime
 * needs of it; a transport (HTTP/2 with JSON, in the host) implements it, reaching a node by its internal
 * [address][me.ahoo.wow.spi.subscription.Member.address].
 *
 * [forward] sends a command to its partition's owner and returns the owner's reply. It throws a
 * [ClusterChannelException] when the peer cannot be reached; it does not time out by itself: the caller bounds it,
 * and a cancelled call leaves the peer's execution running, as a dropped connection would.
 */
fun interface ClusterChannel {
    suspend fun forward(address: String, request: ForwardCommand): ForwardReply
}

/** What a node serves on the cluster channel: the forwarded commands it executes. */
fun interface ClusterChannelHandler {
    suspend fun forward(request: ForwardCommand): ForwardReply
}

/** A peer could not be reached over the cluster channel. */
class ClusterChannelException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * A [ClusterChannel] within one process, for tests and single-process samples: nodes [bind] their handlers to their
 * addresses. Each message still goes through its JSON wire form ([ClusterCodec]) both ways, and the handler runs in
 * [scope], apart from the caller, so a caller that gives up does not stop the owner, as over a network.
 *
 * [latency] runs after the handler and before the reply is returned, to delay replies from an address.
 */
class LoopbackClusterChannel(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val latency: suspend (address: String) -> Unit = {},
) : ClusterChannel {
    private val handlers = ConcurrentHashMap<String, ClusterChannelHandler>()

    /** Serves [handler] at [address]. */
    fun bind(address: String, handler: ClusterChannelHandler) {
        check(handlers.putIfAbsent(address, handler) == null) { "$address is already bound." }
    }

    /** Stops serving [address]: forwards to it are refused, as by a node that is down. */
    fun unbind(address: String) {
        handlers.remove(address)
    }

    override suspend fun forward(address: String, request: ForwardCommand): ForwardReply {
        val handler = handlers[address] ?: throw ClusterChannelException("Connection refused: $address")
        val sent = ClusterCodec.encode(request)
        val reply = scope.async {
            val bytes = ClusterCodec.encode(handler.forward(ClusterCodec.decodeCommand(sent)))
            latency(address)
            bytes
        }
        return ClusterCodec.decodeReply(reply.await())
    }
}
