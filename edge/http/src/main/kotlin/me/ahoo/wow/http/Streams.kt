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

package me.ahoo.wow.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * The first element of a stream a producer hands to a server-sent response, read before the response starts (so a
 * failure is still a problem); null when the stream ended without one, the failure it ended with thrown.
 *
 * The producer runs outside the request (the response's flow reads the rest after the handler returned), so nothing
 * cancels it when the request goes away: when the request is cancelled while waiting (the client disconnected before
 * the first element), the channel is cancelled here, which cancels the producer and closes what it reads, such as a
 * backend's cursor. Once the response reads the channel, its consuming flow cancels it in turn; until
 * then [handingOver] does.
 */
internal suspend fun <T> ReceiveChannel<T>.receiveFirst(): T? {
    val first = try {
        receiveCatching()
    } catch (cancelled: CancellationException) {
        cancel(cancelled)
        throw cancelled
    }
    first.exceptionOrNull()?.let { throw it }
    return first.getOrNull()
}

/**
 * The first element of this channel when it arrives before [signal] completes, else null, the element left to read:
 * the head of a command's event stream goes out once its command is accepted, and a result before that refuses it. As
 * [receiveFirst], a request cancelled while it waits cancels the channel, and a failure the channel ended with first is
 * thrown.
 */
internal suspend fun <T> ReceiveChannel<T>.receiveBefore(signal: Deferred<*>): T? {
    val first = try {
        select<ChannelResult<T>?> {
            // First: once both are ready, the signal wins, and the element stays in the channel for the stream.
            signal.onAwait { null }
            onReceiveCatching { it }
        }
    } catch (cancelled: CancellationException) {
        cancel(cancelled)
        throw cancelled
    }
    first?.exceptionOrNull()?.let { throw it }
    return first?.getOrNull()
}

/**
 * This flow, with [keepAlive] emitted at once and then every [interval] while it runs: a response's head goes out with
 * its first element, so the first one sends it now, and a gateway's idle timeout does not close a stream whose next
 * element is a long time coming. It hands each element on one at a time, as the flow does.
 */
internal fun <T> Flow<T>.withKeepAlive(interval: Duration, keepAlive: T): Flow<T> = channelFlow {
    send(keepAlive)
    val ticks = launch {
        while (true) {
            delay(interval)
            send(keepAlive)
        }
    }
    collect { send(it) }
    ticks.cancel()
}.buffer(Channel.RENDEZVOUS)

/**
 * The response [respond] builds to answer [body], a flow that reads the rest of this channel after the handler
 * returned, with the channel cancelled whenever that body ends or will not read it: when [body] ends, however it ends
 * (a client that left while the body still emitted what it held before reading the channel); when building the
 * response fails or is cancelled; when the request's coroutine ends cancelled after it (WebFlux then drops the
 * response unwritten); and when the response's write ends before it started [body]. Otherwise the producer, which runs
 * outside the request, would wait on its next send for ever, and so would what it reads.
 */
@Suppress("TooGenericExceptionCaught") // Whatever ends the handover cancels the channel, and is rethrown.
internal suspend fun <T> ReceiveChannel<*>.handingOver(
    body: Flow<T>,
    respond: suspend (Flow<T>) -> ServerResponse,
): ServerResponse {
    val started = AtomicBoolean()
    val handedOver = body.onStart { started.set(true) }.onCompletion { cancel() }
    val response = try {
        respond(handedOver)
    } catch (failure: Throwable) {
        cancel()
        throw failure
    }
    currentCoroutineContext()[Job]?.invokeOnCompletion { cause -> if (cause != null) cancel() }
    return HandedOver(response) { if (!started.get()) cancel() }
}

/**
 * [response], calling [ended] once its write ends, however it ends. A write may end before the client read the body
 * (a test client reads it after the write), so only a body never started is the write's to cancel.
 */
private class HandedOver(private val response: ServerResponse, private val ended: () -> Unit) :
    ServerResponse by response {
    override fun writeTo(exchange: ServerWebExchange, context: ServerResponse.Context): Mono<Void> =
        response.writeTo(exchange, context).doFinally { ended() }
}
