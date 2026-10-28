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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.dataset.QueryDatasetChange
import me.ahoo.wow.query.dataset.QueryDatasetListener
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.memory.InMemoryQueryStorageAdapter
import me.ahoo.wow.query.observe.started
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the [QuerySchemaCatalog] finds the storage of an aggregate model: the [QueryStorageAdapter] of the query
 * backend that serves it, or `null` when no backend does. The Catalog needs a storage's facts only, never its backend.
 */
@InternalWowApi
fun interface QueryStorageLocator {
    fun storage(aggregateType: AggregateType, model: QueryModel): QueryStorageAdapter?

    @InternalWowApi
    companion object {
        /** Locates no storage: every model of the Catalog is unavailable. */
        @JvmField
        val NONE: QueryStorageLocator = QueryStorageLocator { _, _ -> null }
    }
}

/**
 * The Catalog: compiles the query schema of every aggregate model this instance serves, one
 * provider per aggregate type and model, with its [compiler] (by default the model sources merged, see
 * [QueryModelCompiler.of]). Each storage only reports its native facts, through the storage adapter [storages]
 * locates. Gateways and point reads read their schema here.
 *
 * Storage facts (indexes, mappings, validators) change outside deployments, so the catalog is revalidated
 * periodically: each schema of [aggregates] is reloaded and published only if it compiles, otherwise the previous one
 * stays and the failure is reported. A model without a backend, or whose backend reports an
 * [UnavailableQueryStorageAdapter], has nothing to load or revalidate: its provider fails every load and revalidation
 * skips it. Each dataset of [datasets] is compiled as its [QueryModel.DATASET] model, and revalidated with them.
 */
@InternalWowApi
class QuerySchemaCatalog(
    private val storages: QueryStorageLocator = QueryStorageLocator.NONE,
    private val compiler: QueryModelCompiler = QueryModelCompiler.of(),
    /** The aggregate types [versions] and [revalidate] cover. */
    aggregates: Collection<AggregateType> = emptyList(),
    /** Sees every revalidation of one schema: its outcome and how long it took (the 9.x refresh timer's hook). */
    private val listener: QuerySchemaRefreshListener = QuerySchemaRefreshListener.NONE,
    /**
     * The datasets [versions] and [revalidate] cover. A dataset occupies a name slot of its context, so
     * it never takes the name of one of [aggregates]: the Catalog refuses such a dataset now and when it joins later.
     * A dataset that joins, changes or leaves drops its compiled schema, so the next reader compiles the current
     * declaration; the Catalog follows [datasets] before any listener registered after it is built, such as the
     * gateways. Compile datasets with [QueryModelCompiler.of]`(datasets = ...)` and locate their storage with
     * `QueryBackendStorages(datasetBindings = ..., datasets = ...)` over the same instance.
     */
    private val datasets: QueryDatasets = QueryDatasets.NONE,
) {
    private val aggregates = aggregates.distinct()
    private val aggregateNames = this.aggregates.toSet()
    private val providers = ConcurrentHashMap<Pair<AggregateType, QueryModel>, QueryModelSchemaProvider>()
    private val policyProviders = ConcurrentHashMap<AggregateType, QueryModelSchemaProvider>()
    private val inFlight = ConcurrentHashMap<QueryModelSchemaProvider, CompletableDeferred<Status>>()

    init {
        datasets.all.forEach { requireDatasetName(it.name) }
        if (datasets !== QueryDatasets.NONE) {
            datasets.listen(
                object : QueryDatasetListener {
                    override fun validate(change: QueryDatasetChange) {
                        if (change is QueryDatasetChange.Added) requireDatasetName(change.dataset.name)
                    }

                    // Also on an addition: a reader may have asked for the name before it was served.
                    override fun apply(change: QueryDatasetChange) {
                        providers.remove(change.dataset.name to QueryModel.DATASET)
                    }
                },
            )
        }
    }

    private fun requireDatasetName(name: AggregateType) {
        require(name !in aggregateNames) { "Dataset [$name] must not take the name of an aggregate of its context." }
    }

    /** One schema provider of one aggregate's model. */
    private data class Entry(
        val aggregateType: AggregateType,
        val model: QueryModel,
        val provider: QueryModelSchemaProvider,
    )

    /** The state of one schema: its content-hash [version], or the [error] that kept it from loading. */
    @InternalWowApi
    data class Status(
        val aggregate: String,
        val model: QueryModel,
        val version: String? = null,
        val error: String? = null,
    )

    /** The schema provider of [aggregateType]'s [model], compiled once and shared by every reader. */
    fun provider(aggregateType: AggregateType, model: QueryModel): QueryModelSchemaProvider =
        providers.computeIfAbsent(aggregateType to model) { create(aggregateType, model) }

    /**
     * The snapshot schema [aggregateType]'s query policies are evaluated against when no backend serves its snapshot
     * queries: compiled from its declaration over the in-memory storage's facts, so a state of it can
     * be admitted in memory ([me.ahoo.wow.query.state.SnapshotPolicyGateway]) though it is never queried. It reads no
     * storage, so it is not revalidated.
     */
    @InternalWowApi
    fun policyProvider(aggregateType: AggregateType): QueryModelSchemaProvider =
        policyProviders.computeIfAbsent(aggregateType) {
            compiler.compile(QuerySchemaContext(it, QueryModel.SNAPSHOT), InMemoryQueryStorageAdapter)
        }

    /** The current schema of [aggregateType]'s [model]. */
    suspend fun schema(aggregateType: AggregateType, model: QueryModel): QueryModelSchema =
        provider(aggregateType, model).schema()

    private fun create(aggregateType: AggregateType, model: QueryModel): QueryModelSchemaProvider =
        when (val storage = storages.storage(aggregateType, model)) {
            null -> UnavailableQueryModelSchemaProvider("No query backend is configured for aggregate[$aggregateType].")
            is UnavailableQueryStorageAdapter -> UnavailableQueryModelSchemaProvider(storage.message)
            else -> compiler.compile(QuerySchemaContext(aggregateType, model), storage)
        }

    // Models without a configured backend have nothing to load or revalidate.
    private fun entries(): List<Entry> = (
        aggregates.flatMap { aggregateType ->
            BUILT_IN_MODELS.map { model -> Entry(aggregateType, model, provider(aggregateType, model)) }
        } + datasets.all.map { Entry(it.name, QueryModel.DATASET, provider(it.name, QueryModel.DATASET)) }
        ).filterNot { it.provider is UnavailableQueryModelSchemaProvider }

    /** The current version of every schema ([aggregate]'s only, when given), loading those not loaded yet. */
    suspend fun versions(aggregate: String? = null): List<Status> =
        select(aggregate).map { entry -> entry.status { entry.provider.schema() } }

    /**
     * Reloads every schema now, only [aggregate]'s and only its [model]'s when given; a schema that fails to compile
     * keeps its previous version. Concurrent reloads of one model share the one in flight.
     */
    suspend fun revalidate(aggregate: String? = null, model: QueryModel? = null): List<Status> =
        select(aggregate).filter { model == null || it.model == model }.map { reload(it) }

    private suspend fun reload(entry: Entry): Status {
        val key = entry.provider
        while (true) {
            val candidate = CompletableDeferred<Status>()
            // A reload whose caller was cancelled is run again by the callers sharing it.
            inFlight.putIfAbsent(key, candidate)?.let { running ->
                awaitShared(running)?.let { return it }
                continue
            }
            return reload(entry, key, candidate)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Every failure reaches the waiting callers, then propagates.
    private suspend fun reload(
        entry: Entry,
        key: QueryModelSchemaProvider,
        candidate: CompletableDeferred<Status>,
    ): Status {
        try {
            val before = entry.status { entry.provider.schema() }.version.orEmpty()
            val started = System.nanoTime()
            val status = entry.status { entry.provider.refresh() }
            status.error?.let { error ->
                val outcome = if (before.isEmpty()) "has no published version" else "kept its previous version"
                log.warn { "Query schema [${status.aggregate}/${status.model}] $outcome: $error" }
            }
            val changed = status.error == null && before.isNotEmpty() && status.version != before
            listener.refreshed(status, changed, Duration.ofNanos(System.nanoTime() - started))
            // Leave the in-flight set before the result reaches anyone, so a revalidation started on that result
            // gets a reload of its own.
            inFlight.remove(key, candidate)
            candidate.complete(status)
            return status
        } catch (error: Throwable) {
            inFlight.remove(key, candidate)
            candidate.completeExceptionally(error)
            throw error
        }
    }

    private fun select(aggregate: String?): List<Entry> =
        entries().filter { aggregate == null || it.aggregateType.toString() == aggregate }

    @Suppress("TooGenericExceptionCaught") // Any load failure is the status of the schema.
    private suspend fun Entry.status(schema: suspend () -> QueryModelSchema): Status {
        val name = aggregateType.toString()
        return try {
            Status(name, model, version = schema().version)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Status(name, model, error = error.message ?: error.javaClass.simpleName)
        }
    }

    private companion object {
        val BUILT_IN_MODELS = listOf(QueryModel.SNAPSHOT, QueryModel.EVENTS)
        val log = KotlinLogging.logger { }
    }
}

/** Sees each revalidation of one schema ([QuerySchemaCatalog.revalidate]): where a host records its metrics. */
@InternalWowApi
fun interface QuerySchemaRefreshListener {
    /**
     * [status] is the outcome, a failure when it carries an error; [changed] says the published version moved;
     * [elapsed] is how long the reload took.
     */
    fun refreshed(status: QuerySchemaCatalog.Status, changed: Boolean, elapsed: Duration)

    @InternalWowApi
    companion object {
        @JvmField
        val NONE: QuerySchemaRefreshListener = QuerySchemaRefreshListener { _, _, _ -> }
    }
}

/** How the Catalog compiles one aggregate model: from its context and its storage's facts to a schema provider. */
@InternalWowApi
fun interface QueryModelCompiler {
    fun compile(context: QuerySchemaContext, storage: QueryStorageAdapter): QueryModelSchemaProvider

    @InternalWowApi
    companion object {
        /**
         * Merges the declarations of [sources] into the logical model under [sensitivity] and compiles the storage's
         * facts about it. [datasets] supplies each dataset model's fields and record layout, [events] each aggregate's
         * `events` model's: its declaration, its profile and the revision rule of its storage facts.
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            sources: List<QuerySchemaSource> = emptyList(),
            sensitivity: QuerySensitivityPolicy = QuerySensitivityPolicy.DEFAULT,
            datasets: QueryDatasets = QueryDatasets.NONE,
            events: EventsQuerySchemas? = null,
        ): QueryModelCompiler {
            val declared = listOfNotNull(
                datasets.schemaSource.takeUnless { datasets === QueryDatasets.NONE },
                events,
            ).let { sources + it }
            return QueryModelCompiler { context, storage ->
                val eventsModel = events?.takeIf { context.model == QueryModel.EVENTS }
                DefaultQueryModelSchemaProvider(
                    context = context,
                    sources = declared,
                    adapter = eventsModel?.adapter(context.aggregateType, storage) ?: storage,
                    profile = eventsModel?.profile(context.aggregateType) ?: datasets.profile(context),
                    sensitivity = sensitivity,
                )
            }
        }
    }
}
