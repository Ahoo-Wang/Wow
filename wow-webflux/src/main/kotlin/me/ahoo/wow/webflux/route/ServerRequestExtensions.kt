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

package me.ahoo.wow.webflux.route

import me.ahoo.wow.api.annotation.InternalWowApi
import org.springframework.core.codec.DecodingException
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.server.NotAcceptableStatusException
import org.springframework.web.server.ServerWebInputException
import reactor.core.publisher.Mono
import tools.jackson.core.JacksonException
import tools.jackson.databind.exc.InvalidDefinitionException

private val STREAMING_RESPONSE_MEDIA_TYPES = listOf(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)

private data class ResponsePreference(
    val mediaType: MediaType,
    val quality: Double,
    val acceptOrder: Int,
    val responseOrder: Int,
)

internal fun ServerRequest.acceptsEventStream(): Boolean {
    return preferredResponseMediaType(STREAMING_RESPONSE_MEDIA_TYPES) == MediaType.TEXT_EVENT_STREAM
}

@InternalWowApi
fun ServerRequest.preferredResponseMediaType(supportedMediaTypes: List<MediaType>): MediaType {
    require(supportedMediaTypes.isNotEmpty()) {
        "supportedMediaTypes must not be empty."
    }
    val requestedMediaTypes = headers().accept()
    if (requestedMediaTypes.isEmpty()) {
        return supportedMediaTypes.first()
    }
    return supportedMediaTypes.mapIndexedNotNull { responseOrder, responseMediaType ->
        val effectiveAccept = requestedMediaTypes.withIndex()
            .filter { (_, acceptedMediaType) -> acceptedMediaType.accepts(responseMediaType) }
            .maxWithOrNull(
                compareBy<IndexedValue<MediaType>> { (_, mediaType) -> mediaType.specificity() }
                    .thenBy { (index) -> -index }
            ) ?: return@mapIndexedNotNull null
        ResponsePreference(
            mediaType = responseMediaType,
            quality = effectiveAccept.value.qualityValue,
            acceptOrder = effectiveAccept.index,
            responseOrder = responseOrder,
        )
    }.filter { it.quality > 0.0 }
        .sortedWith(
            compareByDescending<ResponsePreference> { it.quality }
                .thenBy { it.acceptOrder }
                .thenBy { it.responseOrder }
        )
        .firstOrNull()
        ?.mediaType
        ?: throw NotAcceptableStatusException(supportedMediaTypes)
}

/**
 * Maps a failure to read the request body as the route's type to `400` ([ServerWebInputException]).
 *
 * Spring's decoder reports such a failure as a [DecodingException]. A route that binds the body itself, after reading
 * it as a JSON tree (a command route with path or header variables, the command facade), gets Jackson's exception
 * instead; it is wrapped as the decoder wraps it, so the client sees the same error on both kinds of route. An
 * [InvalidDefinitionException] is the server's (the type cannot be bound at all) and stays unmapped, as in the decoder.
 */
@InternalWowApi
fun <T : Any> Mono<T>.mapRequestBodyDecodingException(): Mono<T> =
    onErrorMap({ it is DecodingException || it.isRequestBindingFailure() }) {
        ServerWebInputException("Failed to read HTTP message", null, it.asDecodingException())
    }

private fun Throwable.isRequestBindingFailure(): Boolean =
    this is JacksonException && this !is InvalidDefinitionException

private fun Throwable.asDecodingException(): Throwable =
    if (this is JacksonException) DecodingException("JSON decoding error: $originalMessage", this) else this

private fun MediaType.accepts(responseMediaType: MediaType): Boolean =
    isCompatibleWith(responseMediaType) ||
        (responseMediaType == MediaType.APPLICATION_JSON && subtype.endsWith("+json"))

private fun MediaType.specificity(): Int = when {
    isWildcardType -> 0
    subtype == "*" -> 1
    subtype.startsWith("*+") -> 2
    else -> 3
}
