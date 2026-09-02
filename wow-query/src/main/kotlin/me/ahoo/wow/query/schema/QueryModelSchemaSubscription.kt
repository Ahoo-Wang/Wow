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

package me.ahoo.wow.query.schema

import me.ahoo.wow.api.query.FilterExpression
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

private object QueryModelSchemaContextKey

internal fun QueryModelSchemaProvider.schemaForQuery(): Mono<QueryModelSchema> =
    Mono.deferContextual { context ->
        if (context.hasKey(QueryModelSchemaContextKey)) {
            Mono.just(context.get<QueryModelSchema>(QueryModelSchemaContextKey))
        } else {
            schema()
        }
    }

/**
 * Backend-native Phase 0 bridge that preserves the subscription-pinned Schema and the existing unavailable fallback.
 */
@JvmSynthetic
fun <T : Any> QueryModelSchemaProvider.executeWithQuerySchema(
    mode: QuerySchemaValidationMode,
    filter: FilterExpression,
    unavailableFallback: (() -> Publisher<out T>)? = null,
    execute: (QueryModelSchema) -> Publisher<out T>,
): Flux<T> = schemaForQuery()
    .map<() -> Publisher<out T>> { schema -> { execute(schema) } }
    .onErrorResume(QuerySchemaUnavailableException::class.java) { error ->
        if (unavailableFallback != null && mode.acceptsUnavailableFallback(filter)) {
            Mono.just(unavailableFallback)
        } else {
            Mono.error(error)
        }
    }.flatMapMany { publisher -> Flux.defer { Flux.from(publisher()) } }

internal fun <T : Any> Mono<T>.withQueryModelSchema(schema: QueryModelSchema): Mono<T> =
    contextWrite { it.put(QueryModelSchemaContextKey, schema) }

internal fun <T : Any> Flux<T>.withQueryModelSchema(schema: QueryModelSchema): Flux<T> =
    contextWrite { it.put(QueryModelSchemaContextKey, schema) }
