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
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.spi.wait.Origin
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Sends wait signals to the internal signal entry ([InternalHttp.SIGNALS_PATH]) of the node a wait started on, at its
 * [Origin.address] (design 04 §6.3, decisions D65, D109): the `remote` of a
 * [me.ahoo.wow.runtime.wait.LocalSignalSender]. A signal is written by [ClusterCodec], as every message between the
 * nodes of a service (decision D122).
 *
 * [send] never waits: it puts the signal in its origin's queue of [queueCapacity] and returns. Each origin's queue is
 * sent from by at most [concurrency] requests at a time, each given [timeout], so an origin holds at most
 * `queueCapacity + concurrency + 1` signals (the one more is taken off the queue, waiting for a request). A signal is dropped, never retried,
 * when its queue is full, when its request fails or times out, or when the origin answers other than 2xx: a lost
 * signal costs its waiter a timeout, never a fact. [dropped] counts them.
 *
 * An origin's queue goes away after [idleTimeout] without signals, so the origins of nodes long gone do not
 * accumulate. [close] stops sending; what is queued then is dropped.
 */
class HttpSignalSender(
    private val client: HttpClient = InternalHttp.client(),
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val concurrency: Int = DEFAULT_CONCURRENCY,
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT,
) : SignalSender, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val outboxes = ConcurrentHashMap<String, Outbox>()
    private val drops = AtomicLong()

    @Volatile
    private var closed = false

    init {
        require(queueCapacity >= 1) { "A signal queue holds at least one signal." }
        require(concurrency >= 1) { "At least one signal is sent at a time." }
        require(timeout.isPositive()) { "The signal timeout is positive." }
        require(idleTimeout.isPositive()) { "The idle timeout is positive." }
    }

    /** The signals dropped so far. */
    val dropped: Long get() = drops.get()

    override suspend fun send(origin: Origin, signal: WaitSignal) {
        while (!closed) {
            val outbox = outboxes.computeIfAbsent(origin.address) { Outbox(it) }
            val offered = outbox.queue.trySend(signal)
            when {
                offered.isSuccess -> return
                // The queue went idle and away meanwhile: take the origin's next one.
                offered.isClosed -> continue
                else -> return drop(origin.address, signal, "its queue is full")
            }
        }
        drop(origin.address, signal, "the sender is closed")
    }

    override fun close() {
        closed = true
        scope.cancel()
        outboxes.values.forEach { it.queue.cancel() }
        outboxes.clear()
    }

    private fun drop(address: String, signal: WaitSignal, why: String) {
        drops.incrementAndGet()
        logger.debug { "Dropped the signal of wait ${signal.waitId} to $address: $why." }
    }

    /** The queue of one origin and the coroutine that sends from it. */
    private inner class Outbox(private val address: String) {
        val queue = Channel<WaitSignal>(queueCapacity) { drop(address, it, "the sender stopped") }
        private val permits = Semaphore(concurrency)

        init {
            scope.launch { run() }
        }

        private suspend fun run() {
            while (true) {
                val signal = withTimeoutOrNull(idleTimeout) { queue.receive() } ?: break
                dispatch(signal)
            }
            // Idle: leave first, so new signals open a new queue, then send what came in meanwhile.
            outboxes.remove(address, this)
            queue.close()
            for (signal in queue) {
                dispatch(signal)
            }
        }

        private suspend fun dispatch(signal: WaitSignal) {
            permits.acquire()
            scope.launch {
                try {
                    deliver(address, signal)
                } finally {
                    permits.release()
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Whatever fails a signal drops it (design 04 §6.3).
    private suspend fun deliver(address: String, signal: WaitSignal) {
        try {
            val request = HttpRequest.newBuilder(InternalHttp.uri(address, InternalHttp.SIGNALS_PATH))
                .timeout(timeout.toJavaDuration())
                .header("Content-Type", InternalHttp.JSON)
                .POST(HttpRequest.BodyPublishers.ofByteArray(ClusterCodec.encode(signal)))
                .build()
            val status = client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).await().statusCode()
            if (status !in SUCCESS) {
                drop(address, signal, "it answered HTTP $status")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            drop(address, signal, failure.toString())
        }
    }

    companion object {
        const val DEFAULT_QUEUE_CAPACITY: Int = 1_024
        const val DEFAULT_CONCURRENCY: Int = 16
        val DEFAULT_TIMEOUT: Duration = 1.seconds
        val DEFAULT_IDLE_TIMEOUT: Duration = 1.minutes
        private val SUCCESS = 200..299
        private val logger = KotlinLogging.logger {}
    }
}
