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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * The first element of a stream a producer hands to a server-sent response, read before the response starts (so a
 * failure is still a problem); null when the stream ended without one, the failure it ended with thrown.
 *
 * The producer runs outside the request (the response's flow reads the rest after the handler returned), so nothing
 * cancels it when the request goes away: when the request is cancelled while waiting (the client disconnected before
 * the first element), the channel is cancelled here, which cancels the producer and closes what it reads, such as a
 * backend's cursor (review A3-5). Once the response reads the channel, its consuming flow cancels it in turn; until
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
 * The response [respond] builds, whose body reads the rest of this channel after the handler returned, with the
 * channel cancelled whenever that body will not read it: when building the response fails or is cancelled, when the
 * request's coroutine ends cancelled after it (WebFlux then drops the response unwritten), and when the response's
 * write ends, fails or is cancelled (a client that left before the body was subscribed). Otherwise the producer, which
 * runs outside the request, would wait on its next send for ever, and so would what it reads.
 */
@Suppress("TooGenericExceptionCaught") // Whatever ends the handover cancels the channel, and is rethrown.
internal suspend fun ReceiveChannel<*>.handingOver(respond: suspend () -> ServerResponse): ServerResponse {
    val response = try {
        respond()
    } catch (failure: Throwable) {
        cancel()
        throw failure
    }
    currentCoroutineContext()[Job]?.invokeOnCompletion { cause -> if (cause != null) cancel() }
    return HandedOver(response, this)
}

/** [response], cancelling [channel] once its write ends, however it ends. */
private class HandedOver(
    private val response: ServerResponse,
    private val channel: ReceiveChannel<*>,
) : ServerResponse by response {
    override fun writeTo(exchange: ServerWebExchange, context: ServerResponse.Context): Mono<Void> =
        response.writeTo(exchange, context).doFinally { channel.cancel() }
}
