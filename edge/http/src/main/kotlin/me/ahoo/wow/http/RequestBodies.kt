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

import me.ahoo.wow.api.annotation.InternalWowApi
import org.springframework.core.codec.ByteArrayDecoder
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.http.codec.DecoderHttpMessageReader
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.awaitBodyOrNull

/**
 * The request's body as its bytes, null without one: read under the codecs' in-memory limit, and refused before any of
 * it is read when its `Content-Length` is past that limit (#7001). The refusal is the [DataBufferLimitException] that
 * reading it would end in, so a route answers it as it answers a body past the limit: the public port's 413 problem,
 * the internal port's `TOO_LARGE` reply to a forward. Reactor Netty sends `100 Continue` only once the body is read, so
 * a client that asked for it is answered before it sends the body. A body without a `Content-Length` (chunked) reaches
 * the limit by reading, as before. Wow's routes read their bodies here; an application's routes read theirs their own
 * way, under their own limits.
 */
@InternalWowApi
suspend fun ServerRequest.awaitBytesOrNull(): ByteArray? {
    val length = headers().contentLength()
    if (length.isPresent) {
        val limit = bytesLimit()
        if (limit >= 0 && length.asLong > limit) {
            throw DataBufferLimitException("Exceeded limit on max bytes to buffer : $limit")
        }
    }
    return awaitBodyOrNull<ByteArray>()
}

/** The in-memory limit the request's bytes are read under: its byte array decoder's, negative for none. */
private fun ServerRequest.bytesLimit(): Int = messageReaders().asSequence()
    .filterIsInstance<DecoderHttpMessageReader<*>>()
    .map { it.decoder }
    .filterIsInstance<ByteArrayDecoder>()
    .firstOrNull()?.maxInMemorySize ?: -1
