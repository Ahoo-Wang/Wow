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

internal fun <T : Any> Mono<T>.withQueryModelSchema(schema: QueryModelSchema): Mono<T> =
    contextWrite { it.put(QueryModelSchemaContextKey, schema) }

internal fun <T : Any> Flux<T>.withQueryModelSchema(schema: QueryModelSchema): Flux<T> =
    contextWrite { it.put(QueryModelSchemaContextKey, schema) }
