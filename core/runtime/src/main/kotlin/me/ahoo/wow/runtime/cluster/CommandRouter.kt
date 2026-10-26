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
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.ExecutionLease
import me.ahoo.wow.runtime.wait.ResultCodes
import me.ahoo.wow.runtime.wait.Settlement
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.commandFunction
import me.ahoo.wow.runtime.wait.failed
import me.ahoo.wow.runtime.wait.reachedResult
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.observation.trace
import me.ahoo.wow.spi.subscription.Member
import me.ahoo.wow.spi.wait.WaitContext
import me.ahoo.wow.spi.wait.toContext
import me.ahoo.wow.spi.wait.toWire
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
 * - A forward the owner surely did not receive degrades to running locally, without retrying it (D71, D223): no
 *   known owner, the peer's [ClusterOptions.maxInFlightPerPeer] reached, the peer unreachable or not connected by the
 *   wait's deadline ([ClusterChannelException.unsent]), or a refusal ([Refusal.NOT_OWNER], [Refusal.EXPIRED], or a
 *   type the peer does not know): the peer did not run it.
 * - A forward whose wait ran out before it could be sent is withdrawn ([ResultCodes.WAIT_TIMEOUT], review R2.1-9):
 *   neither sent nor run here, so it is not reported unknown.
 * - A forward that was sent and got no reply by its deadline, or failed after it was sent, does not degrade (decision
 *   D223, revising D80): the owner may be deciding it still, and deciding it here too would run a decide that does
 *   I/O twice (D46). Its outcome is [ResultCodes.UNKNOWN]; a retry with the same request id is answered from the
 *   owner's commit, if any. The forward timeout only says when to report that.
 *
 * A failed forward also refreshes the routes. Degrading is safe without fencing: the kernel deduplicates by request
 * id, and version-unique keys turn concurrent writers into conflicts that are decided again (D45).
 *
 * A command runs under [ExecutionLease.HELD] when this node holds its partition, else under [ExecutionLease.NOT_HELD]
 * (decision D261): only the owner's kernel caches the states it loads and lets a decide that does I/O skip the
 * request-id lookup.
 */
@InternalWowApi
class PartitionedRouter(
    groups: Collection<PartitionOwnership>,
    private val channel: ClusterChannel,
    private val options: ClusterOptions = ClusterOptions(),
    /** Sees the forwards that did not get the owner's settlement (review A5-11). */
    private val observer: Observer = Observer.NONE,
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
        ownership.runIfAccepting(partition) { withContext(ExecutionLease.HELD) { local() } }?.let { return it }
        // Run here without the lease (D71): the kernel neither caches its states nor trusts them (decision D261).
        val degraded: suspend () -> Settlement = { withContext(ExecutionLease.NOT_HELD) { local() } }
        val peer = ownership.forwardTarget(partition) ?: return degraded()
        return forward(ownership, peer, model, command, wait) ?: degraded()
    }

    /**
     * The settlement [peer] replied with; null when the forward surely did not reach it, so the command runs here; an
     * unknown outcome when it may have (decision D223).
     */
    @Suppress("TooGenericExceptionCaught") // Whatever fails before the send degrades to Local (D71).
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
            observer.forwardFailed(command.ref.type, FORWARD_BUSY)
            return null
        }
        try {
            val request = try {
                request(peer, model, command, wait)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return unsent(ownership, command, peer, failure.toString())
            } ?: return unsent(ownership, command, peer, "not connected by the wait's deadline")
            if (request.deadline <= clock()) {
                // Nothing left to send it in (review R2.1-9): it was not sent, so its outcome is not unknown.
                return if (wait != null && wait.deadline <= clock()) {
                    expired(model, command)
                } else {
                    unsent(ownership, command, peer, "no time left to send it")
                }
            }
            return send(ownership, peer, model, command, request)
        } finally {
            // An idle peer's counter goes (review A5-14): a peer that left, or changed its address in a rolling
            // update, leaves nothing behind. A forward racing the removal counts on the old counter, a soft bound.
            if (permits.decrementAndGet() == 0) {
                inFlight.remove(peer.address, permits)
            }
        }
    }

    /** The peers with forwards in flight. */
    internal val peersInFlight: Set<String> get() = inFlight.keys

    /** The forward of [command] to [peer], once connected to it; null when not connected by [wait]'s deadline. */
    private suspend fun request(
        peer: Member,
        model: AggregateModel<*>,
        command: Command<*>,
        wait: WaitContext?,
    ): ForwardCommand? {
        val waitLeft = wait?.let { (it.deadline - clock()).coerceAtLeast(0).milliseconds } ?: Duration.INFINITE
        withTimeoutOrNull(waitLeft) { channel.connect(peer.address) } ?: return null
        return ForwardCommand(
            command = ClusterCodec.wire(model, command),
            wait = wait?.toWire(),
            deadline = minOf(clock() + options.forwardTimeout.inWholeMilliseconds, wait?.deadline ?: Long.MAX_VALUE),
            trace = currentCoroutineContext().trace,
        )
    }

    /** Sends [request] to [peer]: its settlement; null when it surely did not arrive or was refused; else unknown. */
    @Suppress("TooGenericExceptionCaught") // A failure after the send leaves the outcome unknown (D223).
    private suspend fun send(
        ownership: PartitionOwnership,
        peer: Member,
        model: AggregateModel<*>,
        command: Command<*>,
        request: ForwardCommand,
    ): Settlement? {
        val reply = try {
            withTimeoutOrNull((request.deadline - clock()).coerceAtLeast(0).milliseconds) {
                channel.forward(peer.address, request)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ClusterChannelException) {
            return if (failure.unsent) {
                unsent(ownership, command, peer, failure.toString())
            } else {
                unknown(ownership, model, command, peer, failure.toString())
            }
        } catch (failure: Exception) {
            return unknown(ownership, model, command, peer, failure.toString())
        }
        return when {
            reply == null -> unknown(ownership, model, command, peer, "no reply by the deadline")
            reply.result != null -> Settlement(reply.result, reply.reached)
            // A refusal is the peer's answer that it did not run the command.
            else -> unsent(ownership, command, peer, "refused: ${reply.refused}")
        }
    }

    /** The forward surely did not reach [peer], or [peer] refused it: the command runs here (D71). */
    private fun unsent(ownership: PartitionOwnership, command: Command<*>, peer: Member, why: String): Settlement? {
        logger.debug { "Forwarding ${command.id} to ${peer.node} at ${peer.address} failed ($why); it runs here." }
        observer.forwardFailed(command.ref.type, FORWARD_LOCAL)
        ownership.refreshSoon()
        return null
    }

    /**
     * The wait of [command] ran out before it could be forwarded (review R2.1-9): it was not sent, so it is not run
     * here either, past its wait, and it is withdrawn as a command waiting in its lane past its deadline is
     * ([ResultCodes.WAIT_TIMEOUT], decision D228 ②): it committed nothing, and a retry with the same request id runs it.
     */
    private fun expired(model: AggregateModel<*>, command: Command<*>): Settlement {
        logger.debug { "The wait of ${command.id} ran out before it was forwarded; it is withdrawn, not sent." }
        val committed = reachedResult(
            command,
            commandFunction(model, command),
            Hop.committed(command.ref.type.context),
            clock(),
        )
        return Settlement(
            committed.failed(
                ResultCodes.WAIT_TIMEOUT,
                "The wait timed out before command ${command.id} on ${command.ref} was sent to its owner; it was " +
                    "withdrawn, not executed.",
            ),
        )
    }

    /**
     * The forward reached [peer], or may have, and no reply came (decision D223): the peer may be deciding the command
     * still, so it does not run here too, where a decide doing I/O would run twice. Its outcome is unknown, which a
     * retry with the same request id settles: the kernel answers it from the commit, if any.
     */
    private fun unknown(
        ownership: PartitionOwnership,
        model: AggregateModel<*>,
        command: Command<*>,
        peer: Member,
        why: String,
    ): Settlement {
        logger.info {
            "Forwarding ${command.id} to ${peer.node} at ${peer.address} got no reply ($why); its outcome is unknown."
        }
        observer.forwardFailed(command.ref.type, FORWARD_UNKNOWN)
        ownership.refreshSoon()
        val committed = reachedResult(
            command,
            commandFunction(model, command),
            Hop.committed(command.ref.type.context),
            clock()
        )
        return Settlement(
            committed.failed(
                ResultCodes.UNKNOWN,
                // Not the peer, its address or why: the client is told the conclusion, the node's log the rest.
                "The owner of ${command.ref} did not reply in time; whether the command committed is unknown. " +
                    "Retry it with the same request id.",
            ),
        )
    }

    private companion object {
        val logger = KotlinLogging.logger {}

        /** The outcomes of [Observer.forwardFailed]. */
        const val FORWARD_BUSY = "busy"
        const val FORWARD_LOCAL = "local"
        const val FORWARD_UNKNOWN = "unknown"
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
            ownership.runIfAccepting(partition) { withContext(ExecutionLease.HELD) { execute() } }
                ?: if (ownership.pending(partition)) withContext(ExecutionLease.NOT_HELD) { execute() } else null
        }
        return settlement?.let { ForwardReply(result = it.committed, reached = it.reached) }
            ?: ForwardReply.refused(Refusal.NOT_OWNER)
    }
}
