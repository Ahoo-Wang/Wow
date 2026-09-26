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

import me.ahoo.wow.query.forInProcessQuery
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicReference

interface QueryModelSchemaProvider {
    fun schema(): Mono<QueryModelSchema>

    fun refresh(): Mono<QueryModelSchema>
}

/**
 * The schema provider of an aggregate model that has no query backend: every load fails with [message].
 * [QuerySchemaCatalog] skips it, since there is nothing to load or revalidate.
 */
class UnavailableQueryModelSchemaProvider(
    private val message: String,
) : QueryModelSchemaProvider {
    override fun schema(): Mono<QueryModelSchema> =
        Mono.error(QuerySchemaUnavailableException(message))

    override fun refresh(): Mono<QueryModelSchema> = schema()
}

/**
 * The Catalog's compilation of one aggregate model (design §5.2): merges the declarations of [sources] into the
 * logical model under [sensitivity], asks the storage [adapter] for its facts about it, and compiles them into the
 * published [QueryModelSchema]. The first load is shared by concurrent callers; a refresh reloads the sources and the
 * storage's native structures and replaces the published schema only when it compiles.
 */
class DefaultQueryModelSchemaProvider(
    private val context: QuerySchemaContext,
    sources: List<QuerySchemaSource>,
    private val adapter: QueryStorageAdapter,
    private val sensitivity: QuerySensitivityPolicy = QuerySensitivityPolicy.DEFAULT,
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
            // Shared by every caller, so the load runs without the first caller's scope or entry.
            .contextWrite { it.forInProcessQuery() }
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
            .contextWrite { it.forInProcessQuery() }
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
                merger.merge(SystemQuerySchemaSource.declaration(context.model), declarations, sensitivity)
            }
            .flatMap { logicalSchema ->
                val facts = if (refresh) adapter.refresh(logicalSchema) else adapter.facts(logicalSchema)
                facts.map { it.compile(context.model, logicalSchema) }
            }
}
