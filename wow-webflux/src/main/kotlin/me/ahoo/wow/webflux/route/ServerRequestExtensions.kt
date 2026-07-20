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

import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.server.NotAcceptableStatusException

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

internal fun ServerRequest.preferredResponseMediaType(supportedMediaTypes: List<MediaType>): MediaType {
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

private fun MediaType.accepts(responseMediaType: MediaType): Boolean =
    isCompatibleWith(responseMediaType) ||
        (responseMediaType == MediaType.APPLICATION_JSON && subtype.endsWith("+json"))

private fun MediaType.specificity(): Int = when {
    isWildcardType -> 0
    subtype == "*" -> 1
    subtype.startsWith("*+") -> 2
    else -> 3
}
