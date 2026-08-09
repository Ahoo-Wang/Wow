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

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.bool
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.range
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.term
import co.elastic.clients.elasticsearch.core.ClosePointInTimeRequest
import co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.search.ResponseBody
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation
import co.elastic.clients.json.JsonData
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.toLinkedHashMap
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono

internal fun interface ElasticsearchEventStreamIndexedWatermarkSource {
    fun checkpoint(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
        physicalIndex: ElasticsearchPhysicalIndex,
    ): Mono<Long>
}

internal data class EventStreamElasticsearchIndexVerificationOptions(
    val aggregateScanPageSize: Int = 256,
    val physicalPageSize: Int = 1_000,
    val pitKeepAlive: String = "1m",
    val checksumLimits: SnapshotCanonicalChecksumLimits = SnapshotCanonicalChecksumLimits(),
) {
    init {
        require(aggregateScanPageSize > 0) { "EventStream authority page size must be positive." }
        require(physicalPageSize > 0) { "EventStream physical page size must be positive." }
        require(pitKeepAlive.isNotBlank()) { "EventStream verification PIT keep-alive must not be blank." }
    }
}

internal class EventStoreEventStreamVerificationSource(
    private val source: ElasticsearchAuthoritativeEventStreamSource,
    private val barrier: ElasticsearchEventStreamMigrationBarrier,
    private val options: EventStreamElasticsearchIndexVerificationOptions =
        EventStreamElasticsearchIndexVerificationOptions(),
) : ElasticsearchAuthoritativeVerificationSource {
    override fun capture(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
    ): Mono<ElasticsearchAuthoritativeVerificationSnapshot> = Mono.defer {
        requireEventStreamVerificationContract(manifest)
        checkpoint(command, manifest).flatMap { before ->
            val accumulator = EventStreamCanonicalChecksumAccumulator(options.checksumLimits)
            scanOrderedAuthoritativeEventStreams(source, manifest.target, options.aggregateScanPageSize)
                .doOnNext { eventStream ->
                    accumulator.accept(eventStream.eventStreamDocumentId(), eventStream.toLinkedHashMap())
                }.then(checkpoint(command, manifest))
                .map { after ->
                    check(before == after) { "EventStream authority advanced during verification." }
                    val evidence = accumulator.finish()
                    ElasticsearchAuthoritativeVerificationSnapshot(
                        manifest.verificationContract.checksumAlgorithm,
                        evidence.count,
                        evidence.identityChecksum,
                        evidence.contentChecksum,
                        before,
                    )
                }
        }
    }.mapEventStreamVerificationErrors(manifest)

    private fun checkpoint(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
    ): Mono<Long> = Mono.defer { barrier.checkpoint(command, manifest) }
        .switchIfEmpty(Mono.error(IllegalStateException("EventStream authority returned no watermark.")))
        .map { watermark ->
            require(watermark >= 0) { "EventStream authority watermark must not be negative." }
            watermark
        }
}

internal class ReactiveElasticsearchEventStreamVerificationSource(
    private val client: ReactiveElasticsearchClient,
    private val indexedWatermarks: ElasticsearchEventStreamIndexedWatermarkSource,
    private val options: EventStreamElasticsearchIndexVerificationOptions =
        EventStreamElasticsearchIndexVerificationOptions(),
) : ElasticsearchPhysicalIndexVerificationSource {
    override fun inspect(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
        physicalIndex: ElasticsearchPhysicalIndex,
    ): Mono<ElasticsearchPhysicalIndexVerificationSnapshot> = Mono.defer {
        requireEventStreamVerificationContract(manifest)
        checkpoint(command, manifest, physicalIndex).flatMap { before ->
            val accumulator = EventStreamCanonicalChecksumAccumulator(options.checksumLimits)
            Mono.usingWhen(
                openPit(physicalIndex),
                { lease -> inspectPages(lease, accumulator) },
                ::closePit,
                { lease, error -> closeAfterError(lease, error) },
                ::closeAfterCancel,
            ).flatMap { total ->
                checkpoint(command, manifest, physicalIndex).map { after ->
                    check(before == after) { "EventStream indexed watermark advanced during verification." }
                    val evidence = accumulator.finish()
                    check(evidence.count == total) { "EventStream physical PIT total changed during verification." }
                    ElasticsearchPhysicalIndexVerificationSnapshot(
                        manifest.verificationContract.checksumAlgorithm,
                        physicalIndex,
                        evidence.count,
                        evidence.identityChecksum,
                        evidence.contentChecksum,
                        versionContinuity = true,
                        watermark = before,
                    )
                }
            }
        }
    }.mapEventStreamVerificationErrors(manifest)

    private fun checkpoint(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
        physicalIndex: ElasticsearchPhysicalIndex,
    ): Mono<Long> = Mono.defer { indexedWatermarks.checkpoint(command, manifest, physicalIndex) }
        .switchIfEmpty(Mono.error(IllegalStateException("EventStream destination returned no watermark.")))
        .map { watermark ->
            require(watermark >= 0) { "EventStream destination watermark must not be negative." }
            watermark
        }

    private fun openPit(physicalIndex: ElasticsearchPhysicalIndex): Mono<EventStreamPitLease> =
        client.openPointInTime(
            OpenPointInTimeRequest.of { request ->
                request.index(physicalIndex.value).keepAlive { keepAlive -> keepAlive.time(options.pitKeepAlive) }
            },
        ).switchIfEmpty(Mono.error(IllegalStateException("EventStream verification PIT was not opened.")))
            .map { response ->
                if (response.id().isBlank() || response.shards().failed() != 0) {
                    throw IllegalStateException("EventStream verification PIT is incomplete.")
                }
                EventStreamPitLease(response.id())
            }

    private fun inspectPages(
        lease: EventStreamPitLease,
        accumulator: EventStreamCanonicalChecksumAccumulator,
    ): Mono<Long> {
        fun inspectPage(after: EventStreamVerificationCursor?, rootTotal: Long?, consumed: Long): Mono<Long> =
            client.search(searchRequest(lease, after), Map::class.java)
                .switchIfEmpty(
                    Mono.error(IllegalStateException("EventStream verification search returned no response.")),
                )
                .flatMap { response ->
                    val currentTotal = validateResponse(response)
                    val exactRootTotal = rootTotal ?: currentTotal
                    check(currentTotal == exactRootTotal - consumed) {
                        "EventStream verification keyset total is not stable and exact."
                    }
                    response.pitId()?.takeIf(String::isNotBlank)?.let(lease::update)
                    val hits = response.hits().hits()
                    var nextCursor: EventStreamVerificationCursor? = null
                    hits.forEach { hit ->
                        if (hit.ignored().isNotEmpty()) {
                            throw IllegalStateException("EventStream verification hit contains ignored fields.")
                        }
                        val identity = hit.id()
                            ?: throw IllegalStateException("EventStream verification hit has no identity.")
                        val document = hit.source()
                            ?: throw IllegalStateException("EventStream verification hit has no source.")
                        accumulator.accept(identity, document)
                        nextCursor = document.toEventStreamCursor()
                    }
                    val nextConsumed = Math.addExact(consumed, hits.size.toLong())
                    if (hits.size < options.physicalPageSize) {
                        Mono.just(exactRootTotal)
                    } else {
                        inspectPage(checkNotNull(nextCursor), exactRootTotal, nextConsumed)
                    }
                }
        return inspectPage(null, null, 0)
    }

    private fun searchRequest(
        lease: EventStreamPitLease,
        after: EventStreamVerificationCursor?,
    ): SearchRequest = SearchRequest.of { request ->
        request.pit { pit -> pit.id(lease.id).keepAlive { keepAlive -> keepAlive.time(options.pitKeepAlive) } }
            .size(options.physicalPageSize)
            .allowPartialSearchResults(false)
            .trackTotalHits { total -> total.enabled(true) }
            .sort { sort ->
                sort.field { field -> field.field(MessageRecords.AGGREGATE_ID).order(SortOrder.Asc) }
            }.sort { sort ->
                sort.field { field -> field.field(MessageRecords.VERSION).order(SortOrder.Asc) }
            }.also { builder -> after?.let { cursor -> builder.query(cursor.afterQuery()) } }
    }

    private fun validateResponse(response: ResponseBody<*>): Long {
        check(!response.timedOut() && response.shards().failed() == 0) {
            "EventStream verification search is incomplete."
        }
        val total = checkNotNull(response.hits().total()) { "EventStream verification search has no exact total." }
        check(total.relation() == TotalHitsRelation.Eq) { "EventStream verification search total is not exact." }
        return total.value()
    }

    private fun closePit(lease: EventStreamPitLease): Mono<Void> = client.closePointInTime(
        ClosePointInTimeRequest.of { request -> request.id(lease.id) },
    ).switchIfEmpty(Mono.error(IllegalStateException("EventStream verification PIT close returned no response.")))
        .flatMap { response ->
            if (!response.succeeded()) {
                Mono.error(IllegalStateException("EventStream verification PIT was not closed."))
            } else {
                Mono.empty()
            }
        }

    private fun closeAfterError(lease: EventStreamPitLease, original: Throwable): Mono<Void> =
        closePit(lease).onErrorResume { closeError ->
            original.addSuppressed(closeError)
            Mono.empty()
        }

    private fun closeAfterCancel(lease: EventStreamPitLease): Mono<Void> = closePit(
        lease,
    ).onErrorResume { Mono.empty() }
}

private data class EventStreamVerificationCursor(val aggregateId: String, val version: Int) {
    fun afterQuery(): Query = bool { outer ->
        outer.should(
            range { query ->
                query.untyped { field ->
                    field.field(MessageRecords.AGGREGATE_ID).gt(JsonData.of(aggregateId))
                }
            },
            bool { sameAggregate ->
                sameAggregate.filter(
                    term { query ->
                        query.field(MessageRecords.AGGREGATE_ID).value(FieldValue.of(aggregateId))
                    },
                    range { query ->
                        query.untyped { field ->
                            field.field(MessageRecords.VERSION).gt(JsonData.of(version))
                        }
                    },
                )
            },
        ).minimumShouldMatch("1")
    }
}

private fun Map<*, *>.toEventStreamCursor(): EventStreamVerificationCursor {
    val aggregateId = this[MessageRecords.AGGREGATE_ID] as? String
        ?: throw IllegalStateException("EventStream verification result has no aggregate id.")
    val version = try {
        this[MessageRecords.VERSION].requireExactEventStreamVersion()
    } catch (error: IllegalArgumentException) {
        throw IllegalStateException("EventStream verification result has no exact version.", error)
    }
    return EventStreamVerificationCursor(aggregateId, version)
}

private class EventStreamPitLease(initialId: String) {
    var id: String = initialId
        private set

    fun update(nextId: String) {
        id = nextId
    }
}

private fun requireEventStreamVerificationContract(manifest: ElasticsearchIndexMigrationManifest) {
    require(manifest.target.documentKind == me.ahoo.wow.query.gateway.QueryDocumentKind.EVENT_STREAM) {
        "Canonical EventStream verification requires an EventStream target."
    }
    require(
        manifest.verificationContract.checksumAlgorithm ==
            ElasticsearchIndexChecksumAlgorithm.CANONICAL_EVENT_STREAM_SHA256_V1,
    ) {
        "Canonical EventStream verification received an unsupported checksum algorithm."
    }
}

private fun <T : Any> Mono<T>.mapEventStreamVerificationErrors(
    manifest: ElasticsearchIndexMigrationManifest,
): Mono<T> = onErrorMap { error ->
    if (error is ElasticsearchIndexLifecycleException) {
        error
    } else {
        ElasticsearchIndexLifecycleException(
            ElasticsearchIndexLifecycleErrorCode.VERIFICATION_FAILED,
            manifest.id,
            "Elasticsearch EventStream verification evidence could not be computed.",
            error,
        )
    }
}
