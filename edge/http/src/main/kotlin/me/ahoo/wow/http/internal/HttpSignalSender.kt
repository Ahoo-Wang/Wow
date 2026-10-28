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
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.ObservedEvent.SignalDropped.Reason
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.wait.Origin
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * Sends wait signals to the internal signal entry ([InternalHttp.SIGNALS_PATH]) of the node a wait started on, at its
 * [Origin.address]: the `remote` of a
 * [me.ahoo.wow.runtime.wait.LocalSignalSender]. A signal is written by [ClusterCodec], as every message between the
 * nodes of a service.
 *
 * [send] never waits: it puts the signal in its origin's queue of [queueCapacity] and returns. Each origin's queue is
 * sent from by at most [concurrency] requests at a time. A request carries what the queue holds then, up to
 * [maxBatch] signals: under a burst, one request per batch instead of one per signal; a lone signal goes
 * as before. An origin that refuses a batch as malformed (HTTP 400: a node of an earlier version reads only single
 * signals) is sent one signal a request from then on.
 *
 * A request is given until the latest deadline of the waits its signals end, as a forward is: a signal
 * is worth delivering while its waiter waits, so an origin slow to take it, its event loops lagging under load, still
 * ends the wait. A fixed limit shorter than the wait dropped such signals, and their waiters answered a committed
 * command as timed out (item 1).
 *
 * A request that fails for a while only, its origin's address not resolving or refusing connections yet, its connect
 * timing out, or the origin answering 503 or 429 (a partition owner being replaced, a node starting), is sent again
 * after [retryBackoff], doubled each time up to [MAX_RETRY_BACKOFF], at most [maxAttempts] times in all, and never
 * past the latest deadline of its waits: the signal of a committed command then still ends its wait,
 * where one failure answered it as timed out. The origin deduplicates a signal it took twice, as one sent by two
 * nodes, so a request that reached it and failed after is safe to send again. An origin's definite
 * answer, any other status, is never retried.
 *
 * A signal is dropped when its queue is full, when its wait is over before it is sent ([ObservedEvent.SignalDropped.Reason.EXPIRED]), when its request
 * fails otherwise, or after its last attempt, or outlives its waits, or when the origin answers other than 2xx: a lost
 * signal costs its waiter a timeout, never a fact. Every drop is counted ([dropped], and [observer]'s
 * [ObservedEvent.SignalDropped] by reason) and logged, at WARN the first time for an origin and every [WARN_EVERY]th time
 * after, at DEBUG otherwise: never silent, never a log line per signal of a burst.
 *
 * An origin's queue goes away after [idleTimeout] without signals, so the origins of nodes long gone do not
 * accumulate. [close] stops sending; what is queued then is dropped.
 *
 * Every signal names this node's [service] in `Wow-Service`, whether its origin is a node of this
 * service or of another one, the origin of a wait that crossed into this one: the signal entry takes a signal from a
 * service that names itself, the internal port being reachable by the platform's own pods alone.
 */
@InternalWowApi
class HttpSignalSender(
    private val client: HttpClient = InternalHttp.client(),
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val concurrency: Int = DEFAULT_CONCURRENCY,
    private val idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT,
    private val service: String? = null,
    /** The most signals one request carries to an origin. */
    private val maxBatch: Int = DEFAULT_MAX_BATCH,
    /** Counts the dropped signals by reason. */
    private val observer: Observer = Observer.NONE,
    /** The time the waits' deadlines are read against, in epoch milliseconds. */
    private val clock: () -> Long = System::currentTimeMillis,
    /** The wait before a failed request is sent again the first time, doubled each time after. */
    private val retryBackoff: Duration = DEFAULT_RETRY_BACKOFF,
    /** The most times a request is sent, the first included. */
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
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
        require(retryBackoff.isPositive()) { "The retry backoff is positive." }
        require(maxAttempts >= 1) { "A request is sent at least once." }
    }

    /** The signals dropped so far. */
    val dropped: Long get() = drops.get()

    override suspend fun send(origin: Origin, signal: WaitSignal, deadline: Long) {
        while (!closed) {
            val outbox = outboxes.computeIfAbsent(origin.address) { Outbox(it) }
            val offered = outbox.queue.trySend(Pending(signal, deadline))
            when {
                offered.isSuccess -> return
                // The queue went idle and away meanwhile: take the origin's next one.
                offered.isClosed -> continue
                else -> return drop(origin.address, signal, Reason.QUEUE_FULL)
            }
        }
        drop(origin.address, signal, Reason.CLOSED)
    }

    override fun close() {
        closed = true
        scope.cancel()
        outboxes.values.forEach { it.queue.cancel() }
        outboxes.clear()
    }

    private fun drop(address: String, signal: WaitSignal, reason: Reason, detail: String = reason.value) {
        val count = drops.incrementAndGet()
        observer.observed(ObservedEvent.SignalDropped(address, reason))
        val message = { "Dropped the signal of wait ${signal.waitId} to $address ($detail); $count dropped so far." }
        if (count == 1L || count % WARN_EVERY == 0L) logger.warn(message) else logger.debug(message)
    }

    /** A queued signal and the deadline of its wait. */
    private class Pending(val signal: WaitSignal, val deadline: Long)

    /** The queue of one origin and the coroutine that sends from it. */
    private inner class Outbox(val address: String) {
        val queue = Channel<Pending>(queueCapacity) { drop(address, it.signal, Reason.CLOSED) }
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
     * request, and the origin marked to take single signals from now on. A request that fails for a while only is
     * sent again, as [attempt] + 1, after its backoff ([retry]).
     */
    private suspend fun deliver(outbox: Outbox, pending: List<Pending>, attempt: Int = 1) {
        val now = clock()
        val live = ArrayList<Pending>(pending.size)
        var latest = now
        for (entry in pending) {
            if (entry.deadline > now) {
                live += entry
                latest = maxOf(latest, entry.deadline)
            } else {
                drop(outbox.address, entry.signal, Reason.EXPIRED, "its wait was over before it was sent")
            }
        }
        if (live.isEmpty()) {
            return
        }
        val batch = live.map { it.signal }
        val body = if (batch.size == 1) ClusterCodec.encode(batch.single()) else ClusterCodec.encodeSignals(batch)
        when (val sent = post(outbox, body, latest - now)) {
            is Sent.Answered -> answered(outbox, live, sent.status, attempt, latest)
            is Sent.Failed -> if (sent.failure.isTransient()) {
                retry(outbox, live, attempt, latest, Reason.FAILED, sent.failure.toString())
            } else {
                live.forEach { drop(outbox.address, it.signal, Reason.FAILED, sent.failure.toString()) }
            }
        }
    }

    /** Acts on [status], the origin's answer to [live]: done, sent one by one, sent again, or dropped. */
    private suspend fun answered(outbox: Outbox, live: List<Pending>, status: Int, attempt: Int, latest: Long) {
        when {
            status in SUCCESS -> Unit
            status == BAD_REQUEST && live.size > 1 -> {
                logger.info { "${outbox.address} takes no batch of signals (HTTP $status): sending them one by one." }
                outbox.singles = true
                live.forEach { deliver(outbox, listOf(it)) }
            }

            status in BUSY -> retry(outbox, live, attempt, latest, Reason.REFUSED, "it answered HTTP $status")
            else -> live.forEach { drop(outbox.address, it.signal, Reason.REFUSED, "it answered HTTP $status") }
        }
    }

    /**
     * Sends [live] again after the backoff of [attempt], when an attempt is left and the backoff ends before
     * [latest], the latest of their waits' deadlines; drops them for [reason] otherwise.
     */
    private suspend fun retry(
        outbox: Outbox,
        live: List<Pending>,
        attempt: Int,
        latest: Long,
        reason: Reason,
        detail: String,
    ) {
        val backoff = backoffOf(attempt)
        if (attempt < maxAttempts && clock() + backoff.inWholeMilliseconds < latest) {
            delay(backoff)
            deliver(outbox, live, attempt + 1)
        } else {
            live.forEach { drop(outbox.address, it.signal, reason, "$detail, after $attempt attempts") }
        }
    }

    /** The wait after [attempt] failed: [retryBackoff], doubled each attempt, at most [MAX_RETRY_BACKOFF]. */
    private fun backoffOf(attempt: Int): Duration =
        minOf(retryBackoff * (1 shl minOf(attempt - 1, MAX_DOUBLINGS)), maxOf(MAX_RETRY_BACKOFF, retryBackoff))

    /** What a request came to: the origin's answer, or the failure that left it without one. */
    private sealed interface Sent {
        class Answered(val status: Int) : Sent

        class Failed(val failure: Exception) : Sent
    }

    /** [outbox]'s origin's answer to [body], given [timeoutMillis], or the failure of the request. */
    @Suppress("TooGenericExceptionCaught") // Whatever fails a request is retried or drops its signals.
    private suspend fun post(outbox: Outbox, body: ByteArray, timeoutMillis: Long): Sent = try {
        val builder = HttpRequest.newBuilder(InternalHttp.uri(outbox.address, InternalHttp.SIGNALS_PATH))
            .timeout(timeoutMillis.milliseconds.toJavaDuration())
            .header("Content-Type", InternalHttp.JSON)
        service?.let { builder.header(WowHeaders.SERVICE, it) }
        val request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build()
        Sent.Answered(client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).await().statusCode())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Sent.Failed(failure)
    }

    @InternalWowApi
    companion object {
        const val DEFAULT_QUEUE_CAPACITY: Int = 1_024
        const val DEFAULT_CONCURRENCY: Int = 16
        val DEFAULT_IDLE_TIMEOUT: Duration = 1.minutes
        const val DEFAULT_MAX_BATCH: Int = 64

        /** The wait before a failed request is sent again the first time. */
        val DEFAULT_RETRY_BACKOFF: Duration = 50.milliseconds

        /** The longest wait between two attempts of a request. */
        val MAX_RETRY_BACKOFF: Duration = 800.milliseconds

        /** The most times a request is sent, the first included: 50 + 100 + 200 + 400 ms of backoff in all. */
        const val DEFAULT_MAX_ATTEMPTS: Int = 5
        private const val MAX_DOUBLINGS = 16

        /** The answers of an origin that takes no signal for a while: unavailable, too many requests. */
        private val BUSY = setOf(503, 429)

        /** A drop is logged at WARN the first time and every this many times after. */
        const val WARN_EVERY: Long = 100

        private const val BAD_REQUEST = 400
        private val SUCCESS = 200..299
        private val logger = KotlinLogging.logger {}
    }
}

/**
 * Whether this failure of a request is one that passes: the origin's address does not resolve yet, it refuses the
 * connection, or the connection times out, as while a node starts or is replaced. A request that timed out after it
 * was sent outlived its waits, and is not sent again.
 */
internal fun Throwable.isTransient(): Boolean = generateSequence(this) { it.cause }.take(MAX_CAUSES).any {
    it is ConnectException || it is HttpConnectTimeoutException || it is UnresolvedAddressException ||
        it is UnknownHostException
}

private const val MAX_CAUSES = 8
