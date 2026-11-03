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
import kotlin.time.Duration

/**
 * The cluster channel: the one internal channel between the nodes of a service,
 * listening on their internal port only and trusted without authentication. This port is what the runtime
 * needs of it; a transport (HTTP/2 with JSON, in the host) implements it, reaching a node by its internal
 * [address][me.ahoo.wow.spi.subscription.Member.address].
 *
 * [forward] sends a command to its partition's owner and returns the owner's reply. It throws a
 * [ClusterChannelException] when the peer cannot be reached, [unsent][ClusterChannelException.unsent] when the
 * request surely did not reach it; it does not time out by itself: the caller bounds it, and a
 * cancelled call leaves the peer's execution running, as a dropped connection would.
 *
 * [connect] makes the channel ready to forward to a peer: a transport whose first message to a peer pays for setting
 * up a connection opens it there, so that cost is not counted against a forward's own timeout. A node
 * connects to each peer as soon as its routes learn the peer's address, and [PartitionedRouter] once more before
 * each forward, which costs nothing once connected.
 *
 * [reserve] takes the stream a forward is sent on before it is sent, so a forward that waits for its turn to a busy
 * peer is known not to have been sent; [PartitionedRouter] reserves one before each forward.
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

    /**
     * Takes one of the streams to [address] a forward is sent on, and returns it once taken. A transport that carries
     * a bounded number of requests to a peer at a time waits here, in order, while they are all taken, with no bound of
     * its own: the caller bounds the wait, and a cancelled call takes nothing. It does not fail: a peer that cannot be
     * reached fails the [ClusterStream.forward]. The default takes nothing, and its stream forwards through [forward].
     */
    suspend fun reserve(address: String): ClusterStream = ClusterStream { request -> forward(address, request) }

    /**
     * How long past a forward's deadline its stream still carries the owner's reply: the owner answers about the
     * deadline (it withdraws a command still queued at its wait's deadline), and [PartitionedRouter] waits that much
     * longer for the reply before it reports the outcome unknown. The default carries none past the deadline.
     */
    val answerGrace: Duration get() = Duration.ZERO
}

/**
 * One stream to a peer, taken by [ClusterChannel.reserve] for one forward: [forward] sends it, after which the channel
 * frees the stream once the reply ended it; [release] frees a stream no forward was sent on.
 */
@InternalWowApi
fun interface ClusterStream {
    /** Sends [request] on this stream, as [ClusterChannel.forward] does; once. */
    suspend fun forward(request: ForwardCommand): ForwardReply

    /** Frees this stream when [forward] was not called; nothing after it. */
    fun release() {}
}

/** What a node serves on the cluster channel: the forwarded commands it executes. */
@InternalWowApi
fun interface ClusterChannelHandler {
    suspend fun forward(request: ForwardCommand): ForwardReply
}

/**
 * A peer could not be reached over the cluster channel. [unsent] says the request surely did not reach the peer (no
 * connection to it, refused or not opened in time, or not sent by its deadline), so the sender may run the command
 * itself while its deadline is ahead; any other failure of a forward may have come after the peer received it.
 */
@InternalWowApi
class ClusterChannelException(
    message: String,
    cause: Throwable? = null,
    val unsent: Boolean = false,
) : RuntimeException(message, cause)
