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
import co.elastic.clients.elasticsearch.core.IndexRequest
import me.ahoo.wow.api.Version
import me.ahoo.wow.api.event.DEFAULT_EVENT_SEQUENCE
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.AggregateIdScanner
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.serialization.toLinkedHashMap
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Clock

/**
 * Reads a bounded page of complete aggregate event histories after [afterAggregateId].
 * Implementations must emit aggregates in id order and each aggregate's streams in version order.
 */
internal fun interface ElasticsearchAuthoritativeEventStreamSource {
    fun scan(
        target: QueryTarget,
        afterAggregateId: String,
        aggregateLimit: Int,
    ): Flux<DomainEventStream>
}

internal class EventStoreAuthoritativeEventStreamSource(
    private val eventStore: EventStore,
) : ElasticsearchAuthoritativeEventStreamSource {
    override fun scan(
        target: QueryTarget,
        afterAggregateId: String,
        aggregateLimit: Int,
    ): Flux<DomainEventStream> {
        require(target.documentKind == QueryDocumentKind.EVENT_STREAM) {
            "Authoritative EventStream scan requires an EventStream query target."
        }
        require(aggregateLimit > 0) { "Authoritative EventStream aggregate limit must be positive." }
        return eventStore.scanAggregateId(target.namedAggregate, afterAggregateId, aggregateLimit)
            .concatMap { aggregateId -> eventStore.load(aggregateId) }
    }
}

internal fun interface ElasticsearchPhysicalEventStreamWriter {
    fun write(index: ElasticsearchPhysicalIndex, eventStream: DomainEventStream): Mono<Void>
}

/** Idempotently replaces one immutable authoritative event-stream document in an exact generation. */
internal class ReactiveElasticsearchPhysicalEventStreamWriter(
    private val client: ReactiveElasticsearchClient,
    private val refresh: Refresh = Refresh.WaitFor,
) : ElasticsearchPhysicalEventStreamWriter {
    override fun write(
        index: ElasticsearchPhysicalIndex,
        eventStream: DomainEventStream,
    ): Mono<Void> = Mono.defer {
        val id = eventStream.eventStreamDocumentId()
        val request = IndexRequest.of<Map<String, Any?>> { builder ->
            builder.index(index.value)
                .id(id)
                .routing(eventStream.aggregateId.id)
                .refresh(refresh)
                .document(eventStream.toLinkedHashMap())
        }
        client.index(request)
            .switchIfEmpty(Mono.error(IllegalStateException("EventStream rebuild write returned no response.")))
            .flatMap { response ->
                if (response.index() != index.value || response.id() != id || response.shards().failed() != 0) {
                    Mono.error(IllegalStateException("EventStream rebuild write response is incomplete."))
                } else {
                    Mono.empty()
                }
            }
    }
}

/**
 * Returns a global accepted-write watermark only while the target is externally paused and fully drained.
 * The same watermark before and after a rebuild proves that the authority did not advance during the copy.
 */
internal fun interface ElasticsearchEventStreamMigrationBarrier {
    fun checkpoint(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
    ): Mono<Long>
}

internal data class ElasticsearchEventStreamRebuildOptions(
    val aggregateScanPageSize: Int = 256,
    val writeConcurrency: Int = 8,
) {
    init {
        require(aggregateScanPageSize > 0) { "EventStream rebuild aggregate page size must be positive." }
        require(writeConcurrency > 0) { "EventStream rebuild write concurrency must be positive." }
    }
}

/** Rebuilds a drained EventStream target from the authoritative EventStore into one exact generation. */
internal class EventStoreElasticsearchEventStreamIndexRebuilder(
    private val source: ElasticsearchAuthoritativeEventStreamSource,
    private val writer: ElasticsearchPhysicalEventStreamWriter,
    private val barrier: ElasticsearchEventStreamMigrationBarrier,
    private val clock: Clock,
    private val options: ElasticsearchEventStreamRebuildOptions = ElasticsearchEventStreamRebuildOptions(),
) : ElasticsearchAuthoritativeIndexRebuilder {
    override fun rebuild(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
    ): Mono<ElasticsearchIndexRebuildReceipt> = Mono.defer {
        requirePauseAndDrainManifest(manifest)
        checkpoint(command, manifest).flatMap { before ->
            scanOrderedAuthoritativeEventStreams(source, manifest.target, options.aggregateScanPageSize)
                .flatMapSequential(
                    { eventStream -> writer.write(manifest.names.physical, eventStream) },
                    options.writeConcurrency,
                    1,
                ).then(checkpoint(command, manifest))
                .flatMap { after ->
                    if (before != after) {
                        eventStreamLifecycleError(
                            ElasticsearchIndexLifecycleErrorCode.VALIDATION_FAILED,
                            manifest.id,
                            "EventStream authority advanced while its migration write fence was held.",
                        )
                    } else {
                        Mono.just(
                            ElasticsearchIndexRebuildReceipt(
                                manifest.names.physical,
                                manifest.rebuildStrategy,
                                before,
                                after,
                                clock.instant(),
                            ),
                        )
                    }
                }
        }
    }

    private fun checkpoint(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
    ): Mono<Long> = Mono.defer { barrier.checkpoint(command, manifest) }
        .switchIfEmpty(
            eventStreamLifecycleError(
                ElasticsearchIndexLifecycleErrorCode.VALIDATION_FAILED,
                manifest.id,
                "EventStream migration write fence returned no watermark.",
            ),
        ).flatMap { watermark ->
            if (watermark < 0) {
                eventStreamLifecycleError(
                    ElasticsearchIndexLifecycleErrorCode.VALIDATION_FAILED,
                    manifest.id,
                    "EventStream migration write fence returned a negative watermark.",
                )
            } else {
                Mono.just(watermark)
            }
        }

    private fun requirePauseAndDrainManifest(manifest: ElasticsearchIndexMigrationManifest) {
        if (
            manifest.target.documentKind != QueryDocumentKind.EVENT_STREAM ||
            manifest.rebuildStrategy != ElasticsearchIndexRebuildStrategy.EVENT_STREAM_PAUSE_AND_DRAIN
        ) {
            reject(
                ElasticsearchIndexLifecycleErrorCode.VALIDATION_FAILED,
                manifest.id,
                "EventStore EventStream rebuild requires the pause-and-drain strategy.",
            )
        }
    }
}

internal fun scanOrderedAuthoritativeEventStreams(
    source: ElasticsearchAuthoritativeEventStreamSource,
    target: QueryTarget,
    aggregatePageSize: Int,
): Flux<DomainEventStream> {
    require(target.documentKind == QueryDocumentKind.EVENT_STREAM) {
        "Authoritative EventStream scan requires an EventStream query target."
    }
    require(aggregatePageSize > 0) { "Authoritative EventStream aggregate page size must be positive." }

    fun scanPage(afterAggregateId: String): Flux<DomainEventStream> = Flux.defer {
        val validator = AuthoritativeEventStreamPageValidator(target, afterAggregateId, aggregatePageSize)
        source.scan(target, afterAggregateId, aggregatePageSize)
            .doOnNext(validator::accept)
            .concatWith(
                Flux.defer {
                    if (validator.aggregateCount == aggregatePageSize) {
                        scanPage(validator.lastAggregateId)
                    } else {
                        Flux.empty()
                    }
                },
            )
    }
    return scanPage(AggregateIdScanner.FIRST_ID)
}

private class AuthoritativeEventStreamPageValidator(
    private val target: QueryTarget,
    private val afterAggregateId: String,
    private val aggregateLimit: Int,
) {
    var aggregateCount: Int = 0
        private set
    var lastAggregateId: String = afterAggregateId
        private set
    private var currentAggregateId: String? = null
    private var expectedVersion: Int = Version.INITIAL_VERSION

    fun accept(eventStream: DomainEventStream) {
        val aggregateId = eventStream.aggregateId
        check(aggregateId.namedAggregate.materialize() == target.namedAggregate) {
            "Authoritative EventStream source returned another Aggregate."
        }
        if (aggregateId.id != currentAggregateId) {
            check(aggregateId.id > lastAggregateId && ++aggregateCount <= aggregateLimit) {
                "Authoritative EventStream source violated its aggregate page contract."
            }
            currentAggregateId = aggregateId.id
            lastAggregateId = aggregateId.id
            expectedVersion = Version.INITIAL_VERSION
        }
        check(eventStream.version == expectedVersion && eventStream.size > 0) {
            "Authoritative EventStream source violated aggregate version continuity."
        }
        check(eventStream.body.size == eventStream.size) {
            "Authoritative EventStream source returned an inconsistent event count."
        }
        eventStream.body.forEachIndexed { index, event ->
            val expectedSequence = Math.addExact(DEFAULT_EVENT_SEQUENCE, index)
            val expectedLast = index == eventStream.body.lastIndex
            check(
                event.aggregateId == aggregateId &&
                    event.version == eventStream.version &&
                    event.sequence == expectedSequence &&
                    event.isLast == expectedLast
            ) {
                "Authoritative EventStream source returned an inconsistent event body."
            }
        }
        expectedVersion = Math.addExact(eventStream.version, eventStream.size)
    }
}

internal fun DomainEventStream.eventStreamDocumentId(): String = "${aggregateId.id}-$version"

private fun <T : Any> eventStreamLifecycleError(
    code: ElasticsearchIndexLifecycleErrorCode,
    id: ElasticsearchIndexMigrationId,
    message: String,
): Mono<T> = Mono.error(ElasticsearchIndexLifecycleException(code, id, message))
