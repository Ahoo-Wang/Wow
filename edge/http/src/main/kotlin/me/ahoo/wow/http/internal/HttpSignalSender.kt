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

package me.ahoo.wow.http.internal

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.client.ServiceCredential
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.wait.Origin
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * Sends wait signals to the internal signal entry ([InternalHttp.SIGNALS_PATH]) of the node a wait started on, at its
 * [Origin.address] (design 04 §6.3, decisions D65, D109): the `remote` of a
 * [me.ahoo.wow.runtime.wait.LocalSignalSender]. A signal is written by [ClusterCodec], as every message between the
 * nodes of a service (decision D122).
 *
 * [send] never waits: it puts the signal in its origin's queue of [queueCapacity] and returns. Each origin's queue is
 * sent from by at most [concurrency] requests at a time. A request carries what the queue holds then, up to
 * [maxBatch] signals (review A5-12): under a burst, one request per batch instead of one per signal; a lone signal goes
 * as before. An origin that refuses a batch as malformed (HTTP 400: a node of an earlier version reads only single
 * signals) is sent one signal a request from then on.
 *
 * A request is given until the latest deadline of the waits its signals end (decision D64), as a forward is: a signal
 * is worth delivering while its waiter waits, so an origin slow to take it, its event loops lagging under load, still
 * ends the wait. A fixed limit shorter than the wait dropped such signals, and their waiters answered a committed
 * command as timed out (roadmap §6, item 1).
 *
 * A signal is dropped, never retried, when its queue is full, when its wait is over before it is sent ([EXPIRED]),
 * when its request fails or outlives its waits, or when the origin answers other than 2xx: a lost signal costs its
 * waiter a timeout, never a fact. Every drop is counted ([dropped], and [observer]'s [Observer.signalDropped] by
 * reason) and logged, at WARN the first time for an origin and every [WARN_EVERY]th time after, at DEBUG otherwise:
 * never silent, never a log line per signal of a burst.
 *
 * An origin's queue goes away after [idleTimeout] without signals, so the origins of nodes long gone do not
 * accumulate. [close] stops sending; what is queued then is dropped.
 *
 * A signal is authenticated as the origin's signal entry takes it (decision D226): to a node of this [service], with
 * the service's cluster token, [clusterToken], as [InternalHttp.CLUSTER_TOKEN]; to a node of another service, the
 * origin of a wait that crossed into this one, with the service's [credential] as `Authorization`, the identity it
 * calls other services with (decision D208), which that service trusts. The cluster token never leaves the service:
 * an origin's service and address come from whoever started or carried the wait, so the token goes only to an
 * address [members] knows as a member of this service's cluster (review R2.3-1). An origin that names this service at
 * any other address is sent the signal without a credential, and refuses it. Without a [service], every origin is
 * taken for one of this service.
 */
@InternalWowApi
class HttpSignalSender(
    private val client: HttpClient = InternalHttp.client(),
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val concurrency: Int = DEFAULT_CONCURRENCY,
    private val idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT,
    private val service: String? = null,
    private val clusterToken: String? = null,
    private val credential: ServiceCredential = ServiceCredential.NONE,
    /** The most signals one request carries to an origin (review A5-12). */
    private val maxBatch: Int = DEFAULT_MAX_BATCH,
    /** Counts the dropped signals by reason (review A5-11). */
    private val observer: Observer = Observer.NONE,
    /** The addresses of this service's nodes, the only ones sent the [clusterToken] (review R2.3-1). */
    private val members: ClusterMembers = ClusterMembers.NONE,
    /** The time the waits' deadlines are read against, in epoch milliseconds. */
    private val clock: () -> Long = System::currentTimeMillis,
) : SignalSender, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val outboxes = ConcurrentHashMap<String, Outbox>()
    private val drops = AtomicLong()

    @Volatile
    private var closed = false

    init {
        require(queueCapacity >= 1) { "A signal queue holds at least one signal." }
        require(concurrency >= 1) { "At least one signal is sent at a time." }
        require(idleTimeout.isPositive()) { "The idle timeout is positive." }
        require(maxBatch >= 1) { "A request carries at least one signal." }
    }

    /** The signals dropped so far. */
    val dropped: Long get() = drops.get()

    override suspend fun send(origin: Origin, signal: WaitSignal, deadline: Long) {
        while (!closed) {
            val outbox = outboxes.computeIfAbsent(origin.address) { Outbox(it, origin.service) }
            val offered = outbox.queue.trySend(Pending(signal, deadline))
            when {
                offered.isSuccess -> return
                // The queue went idle and away meanwhile: take the origin's next one.
                offered.isClosed -> continue
                else -> return drop(origin.address, signal, QUEUE_FULL)
            }
        }
        drop(origin.address, signal, CLOSED)
    }

    override fun close() {
        closed = true
        scope.cancel()
        outboxes.values.forEach { it.queue.cancel() }
        outboxes.clear()
    }

    private fun drop(address: String, signal: WaitSignal, reason: String, detail: String = reason) {
        val count = drops.incrementAndGet()
        observer.signalDropped(address, reason)
        val message = { "Dropped the signal of wait ${signal.waitId} to $address ($detail); $count dropped so far." }
        if (count == 1L || count % WARN_EVERY == 0L) logger.warn(message) else logger.debug(message)
    }

    /** A queued signal and the deadline of its wait. */
    private class Pending(val signal: WaitSignal, val deadline: Long)

    /** The queue of one origin and the coroutine that sends from it. */
    private inner class Outbox(val address: String, val originService: String) {
        val queue = Channel<Pending>(queueCapacity) { drop(address, it.signal, CLOSED) }
        private val permits = Semaphore(concurrency)

        /** Whether the origin refused a batch: it is sent one signal a request. */
        @Volatile
        var singles = false

        init {
            scope.launch { run() }
        }

        private suspend fun run() {
            while (true) {
                val pending = withTimeoutOrNull(idleTimeout) { queue.receive() } ?: break
                dispatch(batch(pending))
            }
            // Idle: leave first, so new signals open a new queue, then send what came in meanwhile.
            outboxes.remove(address, this)
            queue.close()
            for (pending in queue) {
                dispatch(batch(pending))
            }
        }

        /** [first] and what the queue holds behind it, up to [maxBatch]; [first] alone for an origin of [singles]. */
        private fun batch(first: Pending): List<Pending> {
            val batch = ArrayList<Pending>(1)
            batch += first
            while (!singles && batch.size < maxBatch) {
                batch += queue.tryReceive().getOrNull() ?: break
            }
            return batch
        }

        private suspend fun dispatch(batch: List<Pending>) {
            permits.acquire()
            scope.launch {
                try {
                    deliver(this@Outbox, batch)
                } finally {
                    permits.release()
                }
            }
        }
    }

    /**
     * Sends [pending] to [outbox]'s origin in one request, given until the latest of their waits' deadlines; a signal
     * whose wait is over is dropped unsent. A batch the origin refuses as malformed is sent again one signal a
     * request, and the origin marked to take single signals from now on.
     */
    private suspend fun deliver(outbox: Outbox, pending: List<Pending>) {
        val now = clock()
        val live = ArrayList<Pending>(pending.size)
        var latest = now
        for (entry in pending) {
            if (entry.deadline > now) {
                live += entry
                latest = maxOf(latest, entry.deadline)
            } else {
                drop(outbox.address, entry.signal, EXPIRED, "its wait was over before it was sent")
            }
        }
        if (live.isEmpty()) {
            return
        }
        val batch = live.map { it.signal }
        val body = if (batch.size == 1) ClusterCodec.encode(batch.single()) else ClusterCodec.encodeSignals(batch)
        val status = post(outbox, batch, body, latest - now) ?: return
        when {
            status in SUCCESS -> Unit
            status == BAD_REQUEST && batch.size > 1 -> {
                logger.info { "${outbox.address} takes no batch of signals (HTTP $status): sending them one by one." }
                outbox.singles = true
                live.forEach { deliver(outbox, listOf(it)) }
            }

            else -> batch.forEach { drop(outbox.address, it, REFUSED, "it answered HTTP $status") }
        }
    }

    /**
     * The status [outbox]'s origin answered [body] with, given [timeoutMillis]; null when the request failed, its
     * signals dropped.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever fails a request drops its signals (design 04 §6.3).
    private suspend fun post(
        outbox: Outbox,
        batch: List<WaitSignal>,
        body: ByteArray,
        timeoutMillis: Long
    ): Int? = try {
        val builder = HttpRequest.newBuilder(InternalHttp.uri(outbox.address, InternalHttp.SIGNALS_PATH))
            .timeout(timeoutMillis.milliseconds.toJavaDuration())
            .header("Content-Type", InternalHttp.JSON)
        when {
            service != null && service != outbox.originService ->
                credential.authorization()?.let { builder.header(AUTHORIZATION, it) }

            members.contains(outbox.address) -> clusterToken?.let { builder.header(InternalHttp.CLUSTER_TOKEN, it) }

            else -> logger.debug {
                "${outbox.address} is no member of this service's cluster: its signals go without the cluster token."
            }
        }
        val request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build()
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).await().statusCode()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        batch.forEach { drop(outbox.address, it, FAILED, failure.toString()) }
        null
    }

    @InternalWowApi
    companion object {
        const val DEFAULT_QUEUE_CAPACITY: Int = 1_024
        const val DEFAULT_CONCURRENCY: Int = 16
        val DEFAULT_IDLE_TIMEOUT: Duration = 1.minutes
        const val DEFAULT_MAX_BATCH: Int = 64

        /** A drop is logged at WARN the first time and every this many times after. */
        const val WARN_EVERY: Long = 100

        /** The reasons of [Observer.signalDropped]. */
        const val QUEUE_FULL: String = "queue-full"
        const val CLOSED: String = "closed"
        const val EXPIRED: String = "expired"
        const val REFUSED: String = "refused"
        const val FAILED: String = "failed"
        private const val BAD_REQUEST = 400
        private val SUCCESS = 200..299
        private const val AUTHORIZATION = "Authorization"
        private val logger = KotlinLogging.logger {}
    }
}
