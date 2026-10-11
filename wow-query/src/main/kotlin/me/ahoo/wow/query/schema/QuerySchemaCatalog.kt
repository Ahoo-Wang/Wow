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

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.query.QueryMetricsObserver
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the [QuerySchemaCatalog] finds the storage of an aggregate model: the [QueryStorageAdapter] of the query
 * backend that serves it, or `null` when no backend does. The Catalog needs a storage's facts only, never its backend;
 * [QueryBackendStorages][me.ahoo.wow.query.QueryBackendStorages] reads them from the backend factories.
 */
fun interface QueryStorageLocator {
    fun storage(namedAggregate: NamedAggregate, model: QueryModel): QueryStorageAdapter?

    companion object {
        /** Locates no storage: every model of the Catalog is unavailable. */
        @JvmField
        val NONE: QueryStorageLocator = QueryStorageLocator { _, _ -> null }
    }
}

/**
 * The Catalog (design §4, §5.2): compiles the query schema of every aggregate model this instance serves, one
 * provider per aggregate and model, with its [compiler] (by default the model sources merged under the sensitivity
 * policy, see [QueryModelCompiler.of]). Each storage only reports its native facts, through the storage adapter
 * [storages] locates. Gateways, point reads and the capability descriptor all read their schema here.
 *
 * Storage facts (indexes, mappings, validators) change outside deployments, so the catalog is revalidated
 * periodically: each schema of [aggregates] is reloaded and published only if it compiles, otherwise the previous one
 * stays and the failure is reported. A model without a backend, or whose backend reports an
 * [UnavailableQueryStorageAdapter], has nothing to load or revalidate: its provider fails every load and revalidation
 * skips it.
 */
class QuerySchemaCatalog(
    private val storages: QueryStorageLocator = QueryStorageLocator.NONE,
    private val compiler: QueryModelCompiler = QueryModelCompiler.of(),
    /** The aggregates [versions] and [revalidate] cover. */
    aggregates: Collection<NamedAggregate> = emptyList(),
    /**
     * Where revalidation publishes `wow.query.schema.refresh` (timer; tags `context`, `aggregate`, `model`,
     * `outcome`: `success` or `failure`, a failure being a refresh failure) and `wow.query.schema.version.changes`
     * (counter, same tags without `outcome`). `null` publishes nothing.
     */
    private val meterRegistry: MeterRegistry? = null,
    /**
     * The datasets [versions] and [revalidate] cover, each compiled as its [QueryModel.DATASET] model. A dataset
     * occupies its context's name slot, so it never takes the name of one of [aggregates].
     */
    datasets: Collection<NamedAggregate> = emptyList(),
) {
    private val aggregates = aggregates.map { it.materialize() }.distinct()
    private val datasets = datasets.map { it.materialize() }.distinct()

    init {
        val shared = this.datasets.intersect(this.aggregates.toSet())
        require(shared.isEmpty()) { "Datasets must not take the name of an aggregate of their context: $shared." }
    }
    private val providers = ConcurrentHashMap<Pair<MaterializedNamedAggregate, QueryModel>, QueryModelSchemaProvider>()
    private val inFlight = ConcurrentHashMap<QueryModelSchemaProvider, Mono<Status>>()

    /** One schema provider of one aggregate's model. */
    private data class Entry(
        val namedAggregate: NamedAggregate,
        val model: QueryModel,
        val provider: QueryModelSchemaProvider,
    )

    /** The state of one schema: its content-hash [version], or the [error] that kept it from loading. */
    data class Status(
        val aggregate: String,
        val model: QueryModel,
        val version: String? = null,
        val error: String? = null,
    )

    /** The schema provider of [namedAggregate]'s [model], compiled once and shared by every reader. */
    fun provider(namedAggregate: NamedAggregate, model: QueryModel): QueryModelSchemaProvider {
        val materialized = namedAggregate.materialize()
        return providers.computeIfAbsent(materialized to model) { create(materialized, model) }
    }

    /** The current schema of [namedAggregate]'s [model]. */
    fun schema(namedAggregate: NamedAggregate, model: QueryModel): Mono<QueryModelSchema> =
        provider(namedAggregate, model).schema()

    private fun create(namedAggregate: MaterializedNamedAggregate, model: QueryModel): QueryModelSchemaProvider {
        return when (val storage = storages.storage(namedAggregate, model)) {
            null -> UnavailableQueryModelSchemaProvider(
                "No query backend is configured for aggregate[$namedAggregate]."
            )
            is UnavailableQueryStorageAdapter -> UnavailableQueryModelSchemaProvider(storage.message)
            else -> compiler.compile(QuerySchemaContext(namedAggregate, model), storage)
        }
    }

    // Models without a configured backend have nothing to load or revalidate.
    private fun entries(): List<Entry> = (
        aggregates.flatMap { namedAggregate ->
            BUILT_IN_MODELS.map { model -> Entry(namedAggregate, model, provider(namedAggregate, model)) }
        } + datasets.map { Entry(it, QueryModel.DATASET, provider(it, QueryModel.DATASET)) }
        ).filterNot { it.provider is UnavailableQueryModelSchemaProvider }

    /** The current version of every schema, loading those not loaded yet. */
    fun versions(aggregate: String? = null): Flux<Status> = select(aggregate).concatMap { entry ->
        entry.status(entry.provider.schema())
    }

    /**
     * Reloads every schema now, only [aggregate]'s and only its [model]'s when given; a schema that fails to compile
     * keeps its previous version. Concurrent reloads of one model share the one in flight.
     */
    fun revalidate(aggregate: String? = null, model: QueryModel? = null): Flux<Status> =
        select(aggregate).filter { model == null || it.model == model }.concatMap(::reload)

    private fun reload(entry: Entry): Mono<Status> {
        val key = entry.provider
        inFlight[key]?.let { return it }
        lateinit var candidate: Mono<Status>
        candidate = Mono.defer { entry.status(entry.provider.schema()).map { it.version.orEmpty() } }
            .flatMap { before ->
                val started = System.nanoTime()
                entry.status(entry.provider.refresh()).doOnNext { status ->
                    status.error?.let { error ->
                        val outcome = if (before.isEmpty()) "has no published version" else "kept its previous version"
                        log.warn { "Query schema [${status.aggregate}/${status.model}] $outcome: $error" }
                    }
                    entry.record(status, before, Duration.ofNanos(System.nanoTime() - started))
                }
            }
            // Leave the in-flight set before the result reaches anyone: `doFinally` runs only after delivery, so a
            // revalidation started on that result, or by a caller woken on another thread, got the finished reload.
            .doOnTerminate { inFlight.remove(key, candidate) }
            .cache()
        return inFlight.putIfAbsent(key, candidate) ?: candidate
    }

    private fun Entry.record(status: Status, before: String, elapsed: Duration) {
        val registry = meterRegistry ?: return
        val tags = QueryMetricsObserver.baseTags(namedAggregate, model.value)
        Timer.builder(SCHEMA_REFRESH)
            .tags(tags.and(QueryMetricsObserver.OUTCOME_TAG, if (status.error == null) "success" else "failure"))
            .register(registry)
            .record(elapsed)
        if (status.error == null && before.isNotEmpty() && status.version != before) {
            Counter.builder(SCHEMA_VERSION_CHANGES).tags(tags).register(registry).increment()
        }
    }

    private fun select(aggregate: String?): Flux<Entry> = Flux.defer {
        Flux.fromIterable(entries().filter { aggregate == null || it.namedAggregate.toStringWithAlias() == aggregate })
    }

    private fun Entry.status(schema: Mono<QueryModelSchema>): Mono<Status> {
        val name = namedAggregate.toStringWithAlias()
        return schema.map { Status(name, model, version = it.version) }
            .onErrorResume { Mono.just(Status(name, model, error = it.message ?: it.javaClass.simpleName)) }
    }

    companion object {
        const val SCHEMA_REFRESH = "wow.query.schema.refresh"
        const val SCHEMA_VERSION_CHANGES = "wow.query.schema.version.changes"
        private val BUILT_IN_MODELS = listOf(QueryModel.SNAPSHOT, QueryModel.EVENT_STREAM)
        private val log = KotlinLogging.logger { }
    }
}

/** How the Catalog compiles one aggregate model: from its context and its storage's facts to a schema provider. */
fun interface QueryModelCompiler {
    fun compile(context: QuerySchemaContext, storage: QueryStorageAdapter): QueryModelSchemaProvider

    companion object {
        /**
         * Merges the declarations of [sources] into the logical model under [sensitivity] and compiles the storage's
         * facts about it (design §5.2). [legacyDeclarations] is what a 9.1 declaration file means when no source
         * declares that model in 9.2. [datasets] supplies each dataset model's fields and record layout.
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            sources: List<QuerySchemaSource> = emptyList(),
            sensitivity: QuerySensitivityPolicy = QuerySensitivityPolicy.DEFAULT,
            legacyDeclarations: LegacyQuerySchemaDeclarationPolicy = LegacyQuerySchemaDeclarationPolicy.WARN,
            datasets: QueryDatasets = QueryDatasets.NONE,
        ): QueryModelCompiler {
            val declared = if (datasets.all.isEmpty()) sources.toList() else sources + datasets
            return QueryModelCompiler { context, storage ->
                DefaultQueryModelSchemaProvider(
                    context = context,
                    sources = declared,
                    adapter = storage,
                    sensitivity = sensitivity,
                    legacyDeclarationPolicy = legacyDeclarations,
                    profile = datasets.profile(context),
                )
            }
        }
    }
}
