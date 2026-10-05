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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.query.forInProcessQuery
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import java.util.function.LongSupplier

interface QueryModelSchemaProvider {
    fun schema(): Mono<QueryModelSchema>

    fun refresh(): Mono<QueryModelSchema>
}

/**
 * The schema provider of an aggregate model that has no query backend: every load fails with [message].
 * [QuerySchemaCatalog] skips it, since there is nothing to load or revalidate.
 */
internal class UnavailableQueryModelSchemaProvider(
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
 * storage's native structures and replaces the published schema only when it compiles. A
 * [provisional][QueryModelSchema.provisional] schema is never published: it is answered for `provisionalTtl`, so a
 * storage that is never written is not asked again on every query, and the first load after that compiles the
 * storage again. Once the storage exists its schema is the one published, at most `provisionalTtl` after it does.
 */
@InternalWowApi
class DefaultQueryModelSchemaProvider(
    private val context: QuerySchemaContext,
    sources: List<QuerySchemaSource>,
    private val adapter: QueryStorageAdapter,
    private val sensitivity: QuerySensitivityPolicy = QuerySensitivityPolicy.DEFAULT,
    private val provisionalTtl: Duration = DEFAULT_PROVISIONAL_TTL,
    /** The monotonic clock, in nanoseconds, `provisionalTtl` is measured with. */
    private val nanoTime: LongSupplier = LongSupplier(System::nanoTime),
    /** compat(wow<9.2): what a 9.1 declaration file of this model means when no source declares it in 9.2. */
    legacyDeclarationPolicy: LegacyQuerySchemaDeclarationPolicy = LegacyQuerySchemaDeclarationPolicy.WARN,
) : QueryModelSchemaProvider {
    private val sources = sources.toList()
    private val legacyDeclarations = LegacyQuerySchemaDeclarations(context, legacyDeclarationPolicy)
    private val published = AtomicReference<QueryModelSchema>()
    private val kept = AtomicReference<Provisional?>()
    private val firstLoad = AtomicReference<Mono<QueryModelSchema>>()
    private val refreshLoad = AtomicReference<Mono<QueryModelSchema>>()
    private val merger = QuerySchemaMerger()

    override fun schema(): Mono<QueryModelSchema> {
        published.get()?.let { return Mono.just(it) }
        currentProvisional()?.let { return Mono.just(it) }
        firstLoad.get()?.let { return it }

        lateinit var candidate: Mono<QueryModelSchema>
        candidate = Mono.defer {
            published.get()?.let { Mono.just(it) }
                ?: resolve(refresh = false)
        }
            .doOnSuccess { schema ->
                schema?.let { if (it.provisional) keepProvisional(it) else published.compareAndSet(null, it) }
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
                schema?.let {
                    published.set(it.takeUnless(QueryModelSchema::provisional))
                    if (it.provisional) keepProvisional(it) else kept.set(null)
                }
                refreshLoad.compareAndSet(candidate, null)
            }
            .doOnError { refreshLoad.compareAndSet(candidate, null) }
            .contextWrite { it.forInProcessQuery() }
            .share()
        return refreshLoad.compareAndExchange(null, candidate) ?: candidate
    }

    private fun currentProvisional(): QueryModelSchema? =
        kept.get()?.takeIf { nanoTime.asLong - it.expiresAt < 0 }?.schema

    private fun keepProvisional(schema: QueryModelSchema) {
        kept.set(Provisional(schema, nanoTime.asLong + provisionalTtl.toNanos()))
    }

    private class Provisional(val schema: QueryModelSchema, val expiresAt: Long)

    companion object {
        /** How long a provisional schema is answered before the storage is asked again. */
        @JvmField
        val DEFAULT_PROVISIONAL_TTL: Duration = Duration.ofSeconds(2)
    }

    private fun resolve(refresh: Boolean): Mono<QueryModelSchema> =
        Flux.fromIterable(sources)
            .concatMap { source ->
                val declarations = if (refresh) source.refresh(context) else source.load(context)
                declarations.map { source to PrioritizedQuerySchemaDeclaration(source.priority, it) }
            }
            .collectList()
            .zipWith(legacyFiles()) { loaded, legacy ->
                // A 9.2 declaration is one any source but inference supplied: classpath, working directory or bean.
                legacyDeclarations.check(legacy, declared = loaded.any { it.first !is InferredQuerySchemaSource })
                merger.merge(SystemQuerySchemaSource.declaration(context.model), loaded.map { it.second }, sensitivity)
            }
            .flatMap { logicalSchema ->
                val facts = if (refresh) adapter.refresh(logicalSchema) else adapter.facts(logicalSchema)
                facts.map { it.compile(context.model, logicalSchema) }
            }

    /** compat(wow<9.2): the 9.1 declaration files of this model across every source. */
    private fun legacyFiles(): Mono<List<String>> =
        Mono.fromCallable { sources.flatMap { it.listLegacyDeclarations(context) } }
            .subscribeOn(Schedulers.boundedElastic())
}
