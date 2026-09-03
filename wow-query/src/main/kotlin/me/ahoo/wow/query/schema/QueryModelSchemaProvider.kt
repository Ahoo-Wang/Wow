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
import java.util.concurrent.atomic.AtomicReference

interface QuerySchemaBackendAdapter {
    fun resolve(logicalSchema: LogicalQuerySchema): Mono<QueryModelSchema>

    fun refresh(logicalSchema: LogicalQuerySchema): Mono<QueryModelSchema> = resolve(logicalSchema)
}

interface QueryModelSchemaProvider {
    fun schema(): Mono<QueryModelSchema>

    fun refresh(): Mono<QueryModelSchema>
}

internal class UnavailableQueryModelSchemaProvider(
    private val message: String,
) : QueryModelSchemaProvider {
    override fun schema(): Mono<QueryModelSchema> =
        Mono.error(QuerySchemaUnavailableException(message))

    override fun refresh(): Mono<QueryModelSchema> = schema()
}

class DefaultQueryModelSchemaProvider(
    private val context: QuerySchemaContext,
    sources: List<QuerySchemaSource>,
    private val adapter: QuerySchemaBackendAdapter,
) : QueryModelSchemaProvider {
    private val sources = sources.toList()
    private val published = AtomicReference<QueryModelSchema>()
    private val firstLoad = AtomicReference<Mono<QueryModelSchema>>()
    private val refreshLoad = AtomicReference<Mono<QueryModelSchema>>()
    private val merger = QuerySchemaMerger()

    override fun schema(): Mono<QueryModelSchema> {
        published.get()?.let { return Mono.just(it) }
        firstLoad.get()?.let { return it }

        lateinit var candidate: Mono<QueryModelSchema>
        candidate = Mono.defer {
            published.get()?.let { Mono.just(it) }
                ?: resolve(refresh = false)
        }
            .doOnSuccess { schema ->
                schema?.let { published.compareAndSet(null, it) }
                firstLoad.compareAndSet(candidate, null)
            }
            .doOnError { firstLoad.compareAndSet(candidate, null) }
            .cache()
        return firstLoad.compareAndExchange(null, candidate) ?: candidate
    }

    override fun refresh(): Mono<QueryModelSchema> {
        refreshLoad.get()?.let { return it }

        lateinit var candidate: Mono<QueryModelSchema>
        candidate = Mono.defer { resolve(refresh = true) }
            .doOnSuccess { schema ->
                schema?.let(published::set)
                refreshLoad.compareAndSet(candidate, null)
            }
            .doOnError { refreshLoad.compareAndSet(candidate, null) }
            .share()
        return refreshLoad.compareAndExchange(null, candidate) ?: candidate
    }

    private fun resolve(refresh: Boolean): Mono<QueryModelSchema> =
        Flux.fromIterable(sources)
            .concatMap { source ->
                val declarations = if (refresh) source.refresh(context) else source.load(context)
                declarations.map { PrioritizedQuerySchemaDeclaration(source.priority, it) }
            }
            .collectList()
            .map { declarations ->
                merger.merge(SystemQuerySchemaSource.declaration(context.model), declarations)
            }
            .flatMap { logicalSchema ->
                if (refresh) adapter.refresh(logicalSchema) else adapter.resolve(logicalSchema)
            }
}
