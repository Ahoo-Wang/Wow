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

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * The cluster channel (design 02 §3; decisions D24, D26): the one internal channel between the nodes of a service,
 * listening on their internal port only and trusted without authentication (D35). This port is what the runtime
 * needs of it; a transport (HTTP/2 with JSON, in the host) implements it, reaching a node by its internal
 * [address][me.ahoo.wow.spi.subscription.Member.address].
 *
 * [forward] sends a command to its partition's owner and returns the owner's reply. It throws a
 * [ClusterChannelException] when the peer cannot be reached, [unsent][ClusterChannelException.unsent] when the
 * request surely did not reach it (decision D223); it does not time out by itself: the caller bounds it, and a
 * cancelled call leaves the peer's execution running, as a dropped connection would.
 *
 * [connect] makes the channel ready to forward to a peer: a transport whose first message to a peer pays for setting
 * up a connection opens it there, so that cost is not counted against a forward's own timeout (decision D80). A node
 * connects to each peer as soon as its routes learn the peer's address, and [PartitionedRouter] once more before
 * each forward, which costs nothing once connected.
 */
@InternalWowApi
fun interface ClusterChannel {
    suspend fun forward(address: String, request: ForwardCommand): ForwardReply

    /**
     * Opens what a forward to [address] needs, and returns once it is open; at once when it already is. It throws a
     * [ClusterChannelException] when the peer cannot be reached, and bounds itself; a cancelled call leaves the
     * connection opening for the next one. The default needs nothing.
     */
    suspend fun connect(address: String) {}
}

/** What a node serves on the cluster channel: the forwarded commands it executes. */
@InternalWowApi
fun interface ClusterChannelHandler {
    suspend fun forward(request: ForwardCommand): ForwardReply
}

/**
 * A peer could not be reached over the cluster channel. [unsent] says the request surely did not reach the peer (no
 * connection to it, refused or not opened in time), so the sender may run the command itself (decision D223); any
 * other failure of a forward may have come after the peer received it.
 */
@InternalWowApi
class ClusterChannelException(
    message: String,
    cause: Throwable? = null,
    val unsent: Boolean = false,
) : RuntimeException(message, cause)
