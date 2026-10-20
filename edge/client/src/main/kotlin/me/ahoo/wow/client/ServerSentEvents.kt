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

package me.ahoo.wow.client

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Flow.Publisher
import java.util.concurrent.Flow.Subscriber
import java.util.concurrent.Flow.Subscription

/**
 * One server-sent event (the HTML Standard's `text/event-stream`): its [event] type (`message` when the stream names
 * none), its [data], the lines of its `data` fields joined by `\n`, and its [id], the last `id` field the stream
 * set, null when none did.
 */
internal class ServerSentEvent(val event: String, val data: String, val id: String?) {
    override fun toString(): String = "ServerSentEvent(event=$event, id=$id, data=$data)"
}

/**
 * The events of a `text/event-stream` read from [lines], as a browser's `EventSource` dispatches them: an event ends
 * at a blank line; a line `field: value` (one space after the colon dropped) sets `event`, `data` (appended) or
 * `id` (one holding NUL is ignored), any other field and a line that starts with a colon (a comment) are ignored; an
 * event without data is not dispatched, and neither is one the stream ends in the middle of.
 */
internal fun Flow<String>.serverSentEvents(): Flow<ServerSentEvent> = flow {
    var event: String? = null
    val data = StringBuilder()
    var hasData = false
    var lastId: String? = null
    collect { line ->
        if (line.isEmpty()) {
            if (hasData) emit(ServerSentEvent(event ?: "message", data.toString(), lastId))
            event = null
            data.setLength(0)
            hasData = false
            return@collect
        }
        if (line.startsWith(':')) return@collect
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            "id" -> if ('\u0000' !in value) lastId = value
        }
    }
}

/**
 * The lines of a response body the JDK client publishes ([java.net.http.HttpResponse.BodyHandlers.ofPublisher]),
 * decoded as UTF-8 and split at `\n`, `\r\n` or `\r`, read without blocking a thread: each chunk is asked for once
 * the one before it is read, so the peer's stream waits for the collector. A last line without a line break is
 * emitted too; cancelling the collection cancels the body.
 */
internal fun Publisher<List<ByteBuffer>>.lines(): Flow<String> = flow {
    val subscriber = ChunkSubscriber()
    subscribe(subscriber)
    val decoder = LineDecoder()
    try {
        for (chunk in subscriber.chunks) {
            decoder.feed(chunk).forEach { emit(it) }
            subscriber.next()
        }
        decoder.rest()?.let { emit(it) }
    } finally {
        subscriber.cancel()
    }
}

/** Hands the chunks of a body to a channel, one asked for at a time. */
private class ChunkSubscriber : Subscriber<List<ByteBuffer>> {
    val chunks = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile
    private var subscription: Subscription? = null

    override fun onSubscribe(subscription: Subscription) {
        this.subscription = subscription
        subscription.request(1)
    }

    override fun onNext(item: List<ByteBuffer>) {
        val bytes = ByteArray(item.sumOf { it.remaining() })
        var at = 0
        item.forEach { buffer ->
            val length = buffer.remaining()
            buffer.get(bytes, at, length)
            at += length
        }
        chunks.trySend(bytes)
    }

    override fun onError(throwable: Throwable) {
        chunks.close(throwable)
    }

    override fun onComplete() {
        chunks.close()
    }

    fun next() {
        subscription?.request(1)
    }

    fun cancel() {
        subscription?.cancel()
        chunks.close()
    }
}

/** Splits UTF-8 bytes into lines across chunks: a line ends at `\n`, `\r\n` or `\r`. */
internal class LineDecoder {
    private val line = ByteArrayOutputStream()
    private var afterCarriageReturn = false

    /** The lines [chunk] completes. */
    fun feed(chunk: ByteArray): List<String> {
        val lines = ArrayList<String>()
        chunk.forEach { byte ->
            val skip = afterCarriageReturn && byte == LF
            afterCarriageReturn = false
            when {
                skip -> Unit
                byte == LF -> lines += take()
                byte == CR -> {
                    lines += take()
                    afterCarriageReturn = true
                }
                else -> line.write(byte.toInt())
            }
        }
        return lines
    }

    /** The last line, when the body ends without a line break after it. */
    fun rest(): String? = if (line.size() > 0) take() else null

    private fun take(): String = line.toString(Charsets.UTF_8).also { line.reset() }

    private companion object {
        const val LF: Byte = '\n'.code.toByte()
        const val CR: Byte = '\r'.code.toByte()
    }
}
