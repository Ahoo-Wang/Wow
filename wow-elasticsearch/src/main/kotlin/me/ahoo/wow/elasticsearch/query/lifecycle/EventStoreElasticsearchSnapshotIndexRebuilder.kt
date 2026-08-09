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

@file:OptIn(me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class)

package me.ahoo.wow.elasticsearch.query.lifecycle

import co.elastic.clients.elasticsearch._types.Refresh
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotVersionGuardedWriter
import me.ahoo.wow.elasticsearch.eventsourcing.toElasticsearchSnapshotWrite
import me.ahoo.wow.eventsourcing.AggregateIdScanner
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.EventStoreStateAggregateRepository
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryTarget
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Clock

internal fun interface ElasticsearchAuthoritativeSnapshotSource {
    fun scan(
        target: QueryTarget,
        afterId: String,
        limit: Int,
    ): Flux<ReadOnlyStateAggregate<*>>
}

internal class EventStoreAuthoritativeSnapshotSource(
    private val eventStore: EventStore,
    private val repository: StateAggregateRepository = EventStoreStateAggregateRepository(
        ConstructorStateAggregateFactory,
        eventStore,
    ),
) : ElasticsearchAuthoritativeSnapshotSource {
    override fun scan(
        target: QueryTarget,
        afterId: String,
        limit: Int,
    ): Flux<ReadOnlyStateAggregate<*>> {
        require(target.documentKind == QueryDocumentKind.SNAPSHOT) {
            "Authoritative state replay requires a Snapshot query target."
        }
        require(limit > 0) { "Authoritative Snapshot scan limit must be positive." }
        return eventStore.scanAggregateId(target.namedAggregate, afterId, limit)
            .concatMap { aggregateId ->
                if (aggregateId.namedAggregate.materialize() != target.namedAggregate) {
                    Mono.error(IllegalStateException("Authoritative EventStore scan returned another Aggregate."))
                } else {
                    loadLatest(aggregateId)
                }
            }
    }

    private fun loadLatest(aggregateId: AggregateId): Mono<ReadOnlyStateAggregate<*>> =
        repository.load<Any>(aggregateId).flatMap<ReadOnlyStateAggregate<*>> { aggregate ->
            if (aggregate.aggregateId != aggregateId || aggregate.version < 0) {
                Mono.error(IllegalStateException("Authoritative event replay returned an invalid aggregate."))
            } else {
                Mono.just<ReadOnlyStateAggregate<*>>(aggregate)
            }
        }.switchIfEmpty(
            Mono.error(IllegalStateException("Authoritative event replay returned no aggregate.")),
        )
}

internal fun interface ElasticsearchPhysicalSnapshotWriter {
    fun write(index: ElasticsearchPhysicalIndex, snapshot: Snapshot<*>): Mono<Void>
}

internal class ReactiveElasticsearchPhysicalSnapshotWriter(
    client: ReactiveElasticsearchClient,
    refresh: Refresh = Refresh.WaitFor,
) : ElasticsearchPhysicalSnapshotWriter {
    private val writer = ElasticsearchSnapshotVersionGuardedWriter(client, refresh)

    override fun write(index: ElasticsearchPhysicalIndex, snapshot: Snapshot<*>): Mono<Void> =
        writer.write(snapshot.toElasticsearchSnapshotWrite(index.value))
}

internal data class ElasticsearchSnapshotRebuildOptions(
    val scanPageSize: Int = 256,
    val writeConcurrency: Int = 8,
) {
    init {
        require(scanPageSize > 0) { "Snapshot rebuild scan page size must be positive." }
        require(writeConcurrency > 0) { "Snapshot rebuild write concurrency must be positive." }
    }
}

/** Rebuilds one Snapshot generation exclusively from the authoritative EventStore. */
internal class EventStoreElasticsearchSnapshotIndexRebuilder(
    private val source: ElasticsearchAuthoritativeSnapshotSource,
    private val writer: ElasticsearchPhysicalSnapshotWriter,
    private val clock: Clock,
    private val options: ElasticsearchSnapshotRebuildOptions = ElasticsearchSnapshotRebuildOptions(),
) : ElasticsearchAuthoritativeIndexRebuilder {
    override fun rebuild(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
    ): Mono<ElasticsearchIndexRebuildReceipt> = Mono.defer {
        requireSnapshotManifest(manifest)
        scanOrderedAuthoritativeSnapshots(source, manifest.target, options.scanPageSize)
            .flatMapSequential(
                { aggregate -> write(manifest, aggregate) },
                options.writeConcurrency,
                1,
            ).count()
            .map {
                ElasticsearchIndexRebuildReceipt(
                    manifest.names.physical,
                    manifest.rebuildStrategy,
                    authoritativeWatermark = null,
                    indexedWatermark = null,
                    completedAt = clock.instant(),
                )
            }
    }

    private fun write(
        manifest: ElasticsearchIndexMigrationManifest,
        aggregate: ReadOnlyStateAggregate<*>,
    ): Mono<Void> = writeSnapshot(manifest, aggregate)

    private fun <S : Any> writeSnapshot(
        manifest: ElasticsearchIndexMigrationManifest,
        aggregate: ReadOnlyStateAggregate<S>,
    ): Mono<Void> = writer.write(
        manifest.names.physical,
        SimpleSnapshot(aggregate, clock.millis()),
    )

    private fun requireSnapshotManifest(manifest: ElasticsearchIndexMigrationManifest) {
        if (
            manifest.target.documentKind != QueryDocumentKind.SNAPSHOT ||
            manifest.rebuildStrategy != ElasticsearchIndexRebuildStrategy.SNAPSHOT_FROM_EVENT_STREAM
        ) {
            reject(
                ElasticsearchIndexLifecycleErrorCode.VALIDATION_FAILED,
                manifest.id,
                "EventStore Snapshot rebuild received an incompatible migration strategy.",
            )
        }
    }
}

internal fun scanOrderedAuthoritativeSnapshots(
    source: ElasticsearchAuthoritativeSnapshotSource,
    target: QueryTarget,
    pageSize: Int,
): Flux<ReadOnlyStateAggregate<*>> {
    require(pageSize > 0) { "Authoritative Snapshot scan page size must be positive." }
    fun scanPage(afterId: String): Flux<ReadOnlyStateAggregate<*>> = Flux.defer {
        source.scan(target, afterId, pageSize)
            .take(pageSize.toLong() + 1)
            .collectList()
            .flatMapMany { page ->
                validateAuthoritativeSnapshotPage(target, afterId, page, pageSize)
                val current = Flux.fromIterable(page)
                if (page.size < pageSize) {
                    current
                } else {
                    current.concatWith(scanPage(page.last().aggregateId.id))
                }
            }
    }
    return scanPage(AggregateIdScanner.FIRST_ID)
}

private fun validateAuthoritativeSnapshotPage(
    target: QueryTarget,
    afterId: String,
    page: List<ReadOnlyStateAggregate<*>>,
    pageSize: Int,
) {
    if (page.size > pageSize) {
        throw IllegalStateException("Authoritative Snapshot source exceeded the requested page size.")
    }
    var previous = afterId
    page.forEach { aggregate ->
        if (
            aggregate.aggregateId.namedAggregate.materialize() != target.namedAggregate ||
            aggregate.aggregateId.id <= previous ||
            aggregate.version < 0
        ) {
            throw IllegalStateException("Authoritative Snapshot source violated its ordered page contract.")
        }
        previous = aggregate.aggregateId.id
    }
}
