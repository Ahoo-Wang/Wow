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
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.query.QueryMetricsObserver
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * The query schemas of every aggregate and model this instance serves. Storage facts (indexes, mappings,
 * validators) change outside deployments, so the catalog is revalidated periodically: each schema is reloaded and
 * published only if it compiles, otherwise the previous one stays and the failure is reported.
 */
class QuerySchemaCatalog(
    entries: List<Entry>,
    /**
     * Where revalidation publishes `wow.query.schema.refresh` (timer; tags `context`, `aggregate`, `model`,
     * `outcome`: `success` or `failure`, a failure being a refresh failure) and `wow.query.schema.version.changes`
     * (counter, same tags without `outcome`). `null` publishes nothing.
     */
    private val meterRegistry: MeterRegistry? = null,
) {
    /** One schema provider of one aggregate's model. */
    data class Entry(val namedAggregate: NamedAggregate, val model: QueryModel, val provider: QueryModelSchemaProvider)

    /** The state of one schema: its content-hash [version], or the [error] that kept it from loading. */
    data class Status(
        val aggregate: String,
        val model: QueryModel,
        val version: String? = null,
        val error: String? = null,
    )

    // Aggregates without a configured backend have nothing to load or revalidate.
    private val entries = entries.filterNot { it.provider is UnavailableQueryModelSchemaProvider }

    /** The current version of every schema, loading those not loaded yet. */
    fun versions(aggregate: String? = null): Flux<Status> = select(aggregate).concatMap { entry ->
        entry.status(entry.provider.schema())
    }

    /** Reloads every schema (or [aggregate]'s) now; a schema that fails to compile keeps its previous version. */
    fun revalidate(aggregate: String? = null): Flux<Status> = select(aggregate).concatMap { entry ->
        val previous = entry.status(entry.provider.schema()).map { it.version.orEmpty() }
        previous.flatMap { before ->
            val started = System.nanoTime()
            entry.status(entry.provider.refresh()).doOnNext { status ->
                status.error?.let { error ->
                    log.warn { "Query schema [${status.aggregate}/${status.model}] kept its previous version: $error" }
                }
                entry.record(status, before, Duration.ofNanos(System.nanoTime() - started))
            }
        }
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

    private fun select(aggregate: String?): Flux<Entry> =
        Flux.fromIterable(entries.filter { aggregate == null || it.namedAggregate.toStringWithAlias() == aggregate })

    private fun Entry.status(schema: Mono<QueryModelSchema>): Mono<Status> {
        val name = namedAggregate.toStringWithAlias()
        return schema.map { Status(name, model, version = it.version) }
            .onErrorResume { Mono.just(Status(name, model, error = it.message ?: it.javaClass.simpleName)) }
    }

    companion object {
        const val SCHEMA_REFRESH = "wow.query.schema.refresh"
        const val SCHEMA_VERSION_CHANGES = "wow.query.schema.version.changes"
        private val log = KotlinLogging.logger { }
    }
}
