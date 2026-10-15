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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.runtime.wait.Settlement
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.observation.trace
import me.ahoo.wow.spi.subscription.Member
import me.ahoo.wow.spi.wait.WaitContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Decides where a command runs (design 02 §2): here, through [local], or on another node. Whatever runs it, the
 * answer is the [Settlement] of its execution.
 */
@InternalWowApi
fun interface CommandRouter {
    suspend fun route(
        model: AggregateModel<*>,
        command: Command<*>,
        wait: WaitContext?,
        local: suspend () -> Settlement,
    ): Settlement

    @InternalWowApi
    companion object {
        /**
         * The `Local` router: every command runs on the node it arrived at, with optimistic concurrency. For a single
         * node, and what `Partitioned` degrades to (decision D71).
         */
        val LOCAL: CommandRouter = CommandRouter { _, _, _, local -> local() }
    }
}

/**
 * The `Partitioned` router (design 02 §2; decisions D6, D71): a command runs on the owner of its log partition, whose
 * state cache is warm and who appends without contention.
 *
 * - A command of a context this node has no lease group for in [groups] (keyed by context) runs locally.
 * - A command for a partition this node holds and accepts runs here, counted for the partition's hand-off.
 * - Otherwise it is forwarded over [channel] to [PartitionOwnership.forwardTarget], waiting for the reply at most
 *   [ClusterOptions.forwardTimeout], capped by the command's own wait deadline (decision D80). The timeout is the
 *   request's: the channel [connects][ClusterChannel.connect] to the peer first, within the wait's deadline, so the
 *   first forward to a peer that started a moment ago does not spend its second opening a connection.
 * - Every failure of a forward degrades to running locally, without retrying it (D71): no known owner, the peer's
 *   [ClusterOptions.maxInFlightPerPeer] reached, the peer unreachable or not connected by the wait's deadline, no
 *   reply in time, or a refusal. A failed forward also refreshes the routes.
 *
 * Degrading is safe without fencing: the kernel deduplicates by request id, so a command the owner did commit before
 * its reply was lost settles here as a duplicate, and version-unique keys turn concurrent writers into conflicts that
 * are decided again (D45).
 */
@InternalWowApi
class PartitionedRouter(
    groups: Collection<PartitionOwnership>,
    private val channel: ClusterChannel,
    private val options: ClusterOptions = ClusterOptions(),
    private val clock: () -> Long = System::currentTimeMillis,
) : CommandRouter {
    private val groups: Map<String, PartitionOwnership> = groups.associateBy { it.group.context }
    private val inFlight = ConcurrentHashMap<String, AtomicInteger>()

    init {
        require(this.groups.size == groups.size) { "A context has one lease group on a node." }
    }

    override suspend fun route(
        model: AggregateModel<*>,
        command: Command<*>,
        wait: WaitContext?,
        local: suspend () -> Settlement,
    ): Settlement {
        val ownership = groups[command.ref.type.context] ?: return local()
        val partition = Partitioner.partitionOf(command.ref, ownership.partitions)
        ownership.runIfAccepting(partition, local)?.let { return it }
        val peer = ownership.forwardTarget(partition) ?: return local()
        return forward(ownership, peer, model, command, wait) ?: local()
    }

    /** The settlement [peer] replied with, or null when the forward failed. */
    @Suppress("TooGenericExceptionCaught") // Every failure of a forward degrades to Local (D71).
    private suspend fun forward(
        ownership: PartitionOwnership,
        peer: Member,
        model: AggregateModel<*>,
        command: Command<*>,
        wait: WaitContext?,
    ): Settlement? {
        val permits = inFlight.computeIfAbsent(peer.address) { AtomicInteger() }
        if (permits.incrementAndGet() > options.maxInFlightPerPeer) {
            permits.decrementAndGet()
            logger.debug { "${peer.node} has ${options.maxInFlightPerPeer} forwards in flight; ${command.id} runs here." }
            return null
        }
        try {
            val waitLeft = wait?.let { (it.deadline - clock()).coerceAtLeast(0).milliseconds } ?: Duration.INFINITE
            withTimeoutOrNull(waitLeft) { channel.connect(peer.address) }
                ?: return failed(ownership, command, peer, "not connected by the wait's deadline")
            val now = clock()
            val deadline = minOf(now + options.forwardTimeout.inWholeMilliseconds, wait?.deadline ?: Long.MAX_VALUE)
            val request = ForwardCommand(
                command = ClusterCodec.wire(model, command),
                wait = wait?.let(WaitWire::of),
                deadline = deadline,
                trace = currentCoroutineContext().trace,
            )
            val reply = withTimeoutOrNull((deadline - now).coerceAtLeast(0).milliseconds) {
                channel.forward(peer.address, request)
            }
            return when {
                reply == null -> failed(ownership, command, peer, "no reply by the deadline")
                reply.result != null -> Settlement(reply.result, reply.reached)
                else -> failed(ownership, command, peer, "refused: ${reply.refused}")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return failed(ownership, command, peer, failure.toString())
        } finally {
            permits.decrementAndGet()
        }
    }

    private fun failed(ownership: PartitionOwnership, command: Command<*>, peer: Member, why: String): Settlement? {
        logger.debug { "Forwarding ${command.id} to ${peer.node} at ${peer.address} failed ($why); it runs here." }
        ownership.refreshSoon()
        return null
    }

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}

/**
 * What a node serves on the cluster channel: it executes the commands other nodes forward to it through [coordinator]
 * (so its own wait context goes on with the command and later hops signal the origin), for the aggregates of
 * [models].
 *
 * It takes a command for a partition it accepts, counted for the partition's hand-off, or one it is about to own
 * ([PartitionOwnership.pending]); a command of a context without a group in [groups] runs as under `Local`. It
 * refuses a partition it neither holds nor is about to hold, a forward past its deadline, and a type it does not
 * know; the sender then runs the command itself. It never forwards again.
 *
 * The command executes in the trace it was forwarded with ([ForwardCommand.trace]), which [observer] resumes
 * (decision D174).
 */
@InternalWowApi
class ForwardEndpoint(
    private val coordinator: WaitCoordinator,
    models: Collection<AggregateModel<*>>,
    groups: Collection<PartitionOwnership>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val observer: Observer = Observer.NONE,
) : ClusterChannelHandler {
    private val models: Map<AggregateType, AggregateModel<*>> = models.associateBy { it.type }
    private val groups: Map<String, PartitionOwnership> = groups.associateBy { it.group.context }

    override suspend fun forward(request: ForwardCommand): ForwardReply {
        val trace = observer.resuming(request.trace)
        return if (trace === EmptyCoroutineContext) forwarded(request) else withContext(trace) { forwarded(request) }
    }

    private suspend fun forwarded(request: ForwardCommand): ForwardReply {
        if (clock() > request.deadline) {
            return ForwardReply.refused(Refusal.EXPIRED)
        }
        val model = models[request.command.ref.type] ?: return ForwardReply.refused(Refusal.UNKNOWN_TYPE)
        val command = ClusterCodec.command(model, request.command) ?: return ForwardReply.refused(Refusal.UNKNOWN_TYPE)
        val wait = request.wait?.toContext()
        val execute: suspend () -> Settlement = { coordinator.executeHere(model, command, wait) }
        val ownership = groups[command.ref.type.context]
        val settlement = if (ownership == null) {
            execute()
        } else {
            val partition = Partitioner.partitionOf(command.ref, ownership.partitions)
            ownership.runIfAccepting(partition, execute)
                ?: if (ownership.pending(partition)) execute() else null
        }
        return settlement?.let { ForwardReply(result = it.committed, reached = it.reached) }
            ?: ForwardReply.refused(Refusal.NOT_OWNER)
    }
}
