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

package me.ahoo.wow.spring.boot.starter.query

import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryStorageAdapter
import reactor.core.publisher.Mono

/** A storage whose facts a [fixedSchemas] compiler never reads. */
internal val FIXED_STORAGE = QueryStorageAdapter { Mono.error(IllegalStateException("Fixed schemas read no facts.")) }

/** Compiles each model to the provider [providers] maps it to, instead of merging sources with storage facts. */
internal fun fixedSchemas(providers: Map<QueryModel, QueryModelSchemaProvider>) =
    QueryModelCompiler { context, _ -> providers.getValue(context.model) }

/** Compiles each model to the one of [schemas] with its model. */
internal fun fixedSchemas(vararg schemas: QueryModelSchema) = fixedSchemas(
    schemas.associate { schema ->
        schema.model to object : QueryModelSchemaProvider {
            override fun schema(): Mono<QueryModelSchema> = Mono.just(schema)

            override fun refresh(): Mono<QueryModelSchema> = schema()
        }
    },
)
