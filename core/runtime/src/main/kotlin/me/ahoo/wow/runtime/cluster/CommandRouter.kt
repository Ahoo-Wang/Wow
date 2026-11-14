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
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.observation.trace
import me.ahoo.wow.spi.subscription.Member
import me.ahoo.wow.spi.wait.WaitContext
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Decides where a command runs: here, through [local], or on another node. Whatever runs it, the
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
         * node, and what `Partitioned` degrades to.
         */
        val LOCAL: CommandRouter = CommandRouter { _, _, _, local -> local() }
    }
}

/**
 * The `Partitioned` router: a command runs on the owner of its log partition, whose
 * state cache is warm and who appends without contention.
 *
 * - A command of a context this node has no lease group for in [groups] (keyed by context) runs locally.
 * - A command for a partition this node holds and accepts runs here, counted for the partition's hand-off.
 * - Otherwise it is forwarded over [channel] to [PartitionOwnership.forwardTarget] by its deadline,
 *   [ClusterOptions.forwardTimeout] from then, capped by the command's own wait deadline. The timeout is the
 *   request's: the channel [connects][ClusterChannel.connect] to the peer first, within the wait's deadline, so the
 *   first forward to a peer that started a moment ago does not spend its second opening a connection. The forward
 *   then [reserves][ClusterChannel.reserve] one of the peer's streams, waiting its turn while they are all taken,
 *   until its deadline, and is sent once it has one.
 * - A forward the owner surely did not receive degrades to running locally, without retrying it: no
 *   known owner, the peer unreachable or not connected by the wait's deadline ([ClusterChannelException.unsent]), or
 *   a refusal before the forward's deadline ([Refusal.NOT_OWNER], [Refusal.EXPIRED], or a type the peer does not
 *   know): the peer did not run it.
 * - A forward to a peer that has [ClusterOptions.maxInFlightPerPeer] forwards from this node in flight is answered
 *   busy ([ResultCodes.BUSY]), as the node's own admission bound answers, and runs nowhere. The bound keeps one slow
 *   peer from taking the node's whole budget of commands in flight; running the command here instead, without the
 *   lease, would make the owner reload the aggregate's state and stall its tail, adding to the load of the peer that
 *   is already slow.
 * - Past its deadline a forward runs nowhere: one not sent by then (no stream to the peer was free, or its wait ran
 *   out before it could be sent), or refused after it, is withdrawn ([ResultCodes.WAIT_TIMEOUT]) once its wait has run
 *   out, as a command still queued in its lane at its wait's deadline is, and answered busy ([ResultCodes.BUSY]) while
 *   it has not, as a full lane is. Either way it was not executed, so it is not reported unknown; and running it here
 *   instead, under the load that kept it from the owner, would put a second writer beside the owner.
 * - A forward that was sent waits for the reply until its deadline and the channel's [ClusterChannel.answerGrace]
 *   after it: the owner answers a command it withdraws at its wait's deadline just after that deadline, and that
 *   answer ([ResultCodes.WAIT_TIMEOUT], not executed) is the outcome. One that got no reply by then, or failed after it
 *   was sent, does not degrade (revising): the owner may be deciding it still, and deciding it here too would run a
 *   decide that does I/O twice. Its outcome is [ResultCodes.UNKNOWN]; a retry with the same request id is answered
 *   from the owner's commit, if any. So [route] answers a forward at most the grace after its deadline; a waited
 *   command's wait still ends at its own deadline.
 *
 * A failed forward also refreshes the routes. Degrading is safe without fencing: the kernel deduplicates by request
 * id, and version-unique keys turn concurrent writers into conflicts that are decided again.
 *
 * A command runs under [ExecutionLease.HELD] when this node holds its partition, else under [ExecutionLease.NOT_HELD]:
 * only the owner's kernel caches the states it loads and lets a decide that does I/O skip the
 * request-id lookup.
 */
@InternalWowApi
class PartitionedRouter(
    groups: Collection<PartitionOwnership>,
    private val channel: ClusterChannel,
    private val options: ClusterOptions = ClusterOptions(),
    /** Sees the forwards that did not get the owner's settlement. */
    private val observer: Observer = Observer.NONE,
    private val clock: Clock = Clock.systemUTC(),
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
        // Run here without the lease: the kernel neither caches its states nor trusts them.
        val degraded: suspend () -> Settlement = { withContext(ExecutionLease.NOT_HELD) { local() } }
        val peer = ownership.forwardTarget(partition) ?: return degraded()
        return forward(ownership, peer, model, command, wait) ?: degraded()
    }

    /**
     * The settlement [peer] replied with; null when the forward surely did not reach it, so the command runs here;
     * busy when [peer] has its most forwards in flight; withdrawn or busy when it was not sent by its deadline; an
     * unknown outcome when it may have reached [peer].
     */
    @Suppress("TooGenericExceptionCaught") // Whatever fails before the send degrades to Local.
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
            return busy(model, command, peer)
        }
        try {
            val request = try {
                request(peer, model, command, wait)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return unsent(ownership, command, peer, failure.toString())
            } ?: return unsent(ownership, command, peer, "not connected by the wait's deadline")
            var stream: ClusterStream? = null
            try {
                // Its turn on the peer's streams, until its deadline: until it has one, it is not sent.
                withTimeoutOrNull(left(request.deadline)) { stream = channel.reserve(peer.address) }
                val reserved = stream
                if (reserved == null || request.deadline <= clock.millis()) {
                    // Nothing left to send it in: it was not sent, so its outcome is not unknown.
                    return late(model, command, request, wait, peer, "no stream to the peer by the deadline")
                }
                return send(ownership, peer, model, command, wait, request, reserved)
            } finally {
                // Frees a stream taken just as the deadline passed; nothing once the forward was sent on it.
                stream?.release()
            }
        } finally {
            // An idle peer's counter goes: a peer that left, or changed its address in a rolling
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
        withTimeoutOrNull(wait?.let { left(it.deadline) } ?: Duration.INFINITE) { channel.connect(peer.address) }
            ?: return null
        val trace = currentCoroutineContext().trace
        return ForwardCommand(
            command = ClusterCodec.wire(model, command),
            transport = if (wait == null && trace.isEmpty()) null else Transport(wait = wait, trace = trace),
            deadline = minOf(
                clock.millis() + options.forwardTimeout.inWholeMilliseconds,
                wait?.deadline ?: Long.MAX_VALUE
            ),
        )
    }

    /**
     * Sends [request] to [peer] on [stream], waiting for the reply until its deadline and the channel's
     * [ClusterChannel.answerGrace] after it: its settlement; null when it surely did not arrive or was refused before
     * its deadline; withdrawn or busy when it was not sent by then or refused after it; else unknown.
     */
    @Suppress("TooGenericExceptionCaught") // A failure after the send leaves the outcome unknown.
    private suspend fun send(
        ownership: PartitionOwnership,
        peer: Member,
        model: AggregateModel<*>,
        command: Command<*>,
        wait: WaitContext?,
        request: ForwardCommand,
        stream: ClusterStream,
    ): Settlement? {
        val reply = try {
            withTimeoutOrNull(left(request.deadline) + channel.answerGrace) { stream.forward(request) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ClusterChannelException) {
            return when {
                !failure.unsent -> unknown(ownership, model, command, peer, failure.toString())
                // Not sent by its deadline, as one the peer's connection kept refusing for its streams.
                request.deadline <= clock.millis() -> late(model, command, request, wait, peer, failure.toString())
                else -> unsent(ownership, command, peer, failure.toString())
            }
        } catch (failure: Exception) {
            return unknown(ownership, model, command, peer, failure.toString())
        }
        return when {
            reply == null -> unknown(ownership, model, command, peer, "no reply by the deadline and its grace")
            reply.result != null -> Settlement(reply.result, reply.reached)
            // A refusal is the peer's answer that it did not run the command; past the deadline, one it took too late.
            request.deadline <= clock.millis() -> late(model, command, request, wait, peer, "refused: ${reply.refused}")
            else -> unsent(ownership, command, peer, "refused: ${reply.refused}")
        }
    }

    /**
     * [peer] has [ClusterOptions.maxInFlightPerPeer] forwards from this node in flight: [command] is refused busy
     * ([ResultCodes.BUSY]), as the node's own admission bound refuses one. It committed nothing and runs nowhere, and a
     * retry with the same request id runs it.
     */
    private fun busy(model: AggregateModel<*>, command: Command<*>, peer: Member): Settlement {
        logger.debug {
            "${peer.node} has ${options.maxInFlightPerPeer} forwards in flight; ${command.id} is refused busy."
        }
        observer.observed(ObservedEvent.ForwardFailed(command.ref.type, ObservedEvent.ForwardFailed.Outcome.BUSY))
        observer.observed(ObservedEvent.Refused(command.ref.type, ResultCodes.BUSY))
        val committed = reachedResult(
            command,
            commandFunction(model, command),
            Hop.committed(command.ref.type.context),
            clock.millis(),
        )
        // Not the peer or its address: the client is told the conclusion, the node's log the rest.
        val message = "The owner of ${command.ref} already has ${options.maxInFlightPerPeer} commands in flight from " +
            "this node; retry later."
        return Settlement(committed.failed(ResultCodes.BUSY, message))
    }

    /** The time left until [deadline], none once it passed. */
    private fun left(deadline: Long): Duration = (deadline - clock.millis()).coerceAtLeast(0).milliseconds

    /** The forward surely did not reach [peer], or [peer] refused it: the command runs here. */
    private fun unsent(ownership: PartitionOwnership, command: Command<*>, peer: Member, why: String): Settlement? {
        logger.debug { "Forwarding ${command.id} to ${peer.node} at ${peer.address} failed ($why); it runs here." }
        observer.observed(ObservedEvent.ForwardFailed(command.ref.type, ObservedEvent.ForwardFailed.Outcome.LOCAL))
        ownership.refreshSoon()
        return null
    }

    /**
     * The forward [request] of [command] to [peer] was not sent by its deadline, or [peer] refused it past it: the
     * command was not executed, and it is not run here either, past the deadline. It is withdrawn once its [wait] has
     * run out, its deadline the forward's or passed since, as a command waiting in its lane past its wait's deadline is
     * ([ResultCodes.WAIT_TIMEOUT]), else answered busy, as a full lane answers ([ResultCodes.BUSY]): it committed
     * nothing, and a retry with the same request id runs it.
     */
    private fun late(
        model: AggregateModel<*>,
        command: Command<*>,
        request: ForwardCommand,
        wait: WaitContext?,
        peer: Member,
        why: String,
    ): Settlement {
        // A forward whose deadline was its wait's ran out with the wait, whatever the clock reads a moment later.
        val expired = wait != null && (wait.deadline <= request.deadline || wait.deadline <= clock.millis())
        logger.debug {
            "Forwarding ${command.id} to ${peer.node} at ${peer.address} did not start by its deadline ($why); it is " +
                if (expired) "withdrawn." else "answered busy."
        }
        val code = if (expired) ResultCodes.WAIT_TIMEOUT else ResultCodes.BUSY
        observer.observed(ObservedEvent.Refused(command.ref.type, code))
        val committed = reachedResult(
            command,
            commandFunction(model, command),
            Hop.committed(command.ref.type.context),
            clock.millis(),
        )
        val message = if (expired) {
            "The wait timed out before the owner of ${command.ref} took command ${command.id}; it was withdrawn, " +
                "not executed."
        } else {
            "The owner of ${command.ref} was too busy to take command ${command.id} before its deadline; it was not " +
                "executed. Retry it later with the same request id."
        }
        return Settlement(committed.failed(code, message))
    }

    /**
     * The forward reached [peer], or may have, and no reply came: the peer may be deciding the command
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
        observer.observed(ObservedEvent.ForwardFailed(command.ref.type, ObservedEvent.ForwardFailed.Outcome.UNKNOWN))
        ownership.refreshSoon()
        val committed = reachedResult(
            command,
            commandFunction(model, command),
            Hop.committed(command.ref.type.context),
            clock.millis()
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
 * The command executes in the trace its forward's transport carries ([ForwardCommand.transport]), which [observer] resumes.
 */
@InternalWowApi
class ForwardEndpoint(
    private val coordinator: WaitCoordinator,
    models: Collection<AggregateModel<*>>,
    groups: Collection<PartitionOwnership>,
    private val clock: Clock = Clock.systemUTC(),
    private val observer: Observer = Observer.NONE,
) : ClusterChannelHandler {
    private val models: Map<AggregateType, AggregateModel<*>> = models.associateBy { it.type }
    private val groups: Map<String, PartitionOwnership> = groups.associateBy { it.group.context }

    override suspend fun forward(request: ForwardCommand): ForwardReply {
        val trace = observer.resuming(request.transport?.trace.orEmpty())
        return if (trace === EmptyCoroutineContext) forwarded(request) else withContext(trace) { forwarded(request) }
    }

    private suspend fun forwarded(request: ForwardCommand): ForwardReply {
        if (clock.millis() > request.deadline) {
            return ForwardReply.refused(Refusal.EXPIRED)
        }
        val model = models[request.command.ref.type] ?: return ForwardReply.refused(Refusal.UNKNOWN_TYPE)
        val command = ClusterCodec.command(model, request.command) ?: return ForwardReply.refused(Refusal.UNKNOWN_TYPE)
        val wait = request.transport?.wait
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
