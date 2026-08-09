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

import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.range
import co.elastic.clients.elasticsearch.core.ClosePointInTimeRequest
import co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.search.ResponseBody
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation
import co.elastic.clients.json.JsonData
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.toLinkedHashMap
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono

internal data class SnapshotElasticsearchIndexVerificationOptions(
    val pageSize: Int = 1_000,
    val pitKeepAlive: String = "1m",
    val checksumLimits: SnapshotCanonicalChecksumLimits = SnapshotCanonicalChecksumLimits(),
) {
    init {
        require(pageSize > 0) { "Snapshot verification page size must be positive." }
        require(pitKeepAlive.isNotBlank()) { "Snapshot verification PIT keep-alive must not be blank." }
    }
}

/** Computes expected Snapshot evidence from full authoritative EventStore replay. */
internal class EventStoreSnapshotVerificationSource(
    private val source: ElasticsearchAuthoritativeSnapshotSource,
    private val options: SnapshotElasticsearchIndexVerificationOptions =
        SnapshotElasticsearchIndexVerificationOptions(),
) : ElasticsearchAuthoritativeVerificationSource {
    override fun capture(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
    ): Mono<ElasticsearchAuthoritativeVerificationSnapshot> = Mono.defer {
        requireSnapshotVerificationContract(manifest)
        val accumulator = SnapshotCanonicalChecksumAccumulator(options.checksumLimits)
        scanOrderedAuthoritativeSnapshots(source, manifest.target, options.pageSize)
            .doOnNext { aggregate ->
                val snapshot = SimpleSnapshot(aggregate, snapshotTime = 0).toLinkedHashMap()
                accumulator.accept(aggregate.aggregateId.id, snapshot)
            }.then(
                Mono.fromSupplier {
                    val evidence = accumulator.finish()
                    ElasticsearchAuthoritativeVerificationSnapshot(
                        manifest.verificationContract.checksumAlgorithm,
                        evidence.count,
                        evidence.identityChecksum,
                        evidence.contentChecksum,
                        watermark = null,
                    )
                },
            )
    }.mapVerificationErrors(manifest)
}

/** Scans one exact physical Snapshot generation through a PIT and computes actual evidence. */
internal class ReactiveElasticsearchSnapshotVerificationSource(
    private val client: ReactiveElasticsearchClient,
    private val options: SnapshotElasticsearchIndexVerificationOptions =
        SnapshotElasticsearchIndexVerificationOptions(),
) : ElasticsearchPhysicalIndexVerificationSource {
    override fun inspect(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
        physicalIndex: ElasticsearchPhysicalIndex,
    ): Mono<ElasticsearchPhysicalIndexVerificationSnapshot> = Mono.defer {
        requireSnapshotVerificationContract(manifest)
        val accumulator = SnapshotCanonicalChecksumAccumulator(options.checksumLimits)
        Mono.usingWhen(
            openPit(physicalIndex),
            { lease -> inspectPages(lease, accumulator) },
            ::closePit,
            { lease, error -> closeAfterError(lease, error) },
            ::closeAfterCancel,
        ).map { total ->
            val evidence = accumulator.finish()
            if (evidence.count != total) {
                throw IllegalStateException("Physical Snapshot PIT total changed during verification.")
            }
            ElasticsearchPhysicalIndexVerificationSnapshot(
                manifest.verificationContract.checksumAlgorithm,
                physicalIndex,
                evidence.count,
                evidence.identityChecksum,
                evidence.contentChecksum,
                versionContinuity = true,
                watermark = null,
            )
        }
    }.mapVerificationErrors(manifest)

    private fun openPit(physicalIndex: ElasticsearchPhysicalIndex): Mono<PitLease> =
        client.openPointInTime(
            OpenPointInTimeRequest.of { request ->
                request.index(physicalIndex.value).keepAlive { keepAlive -> keepAlive.time(options.pitKeepAlive) }
            },
        ).switchIfEmpty(Mono.error(IllegalStateException("Snapshot verification PIT was not opened.")))
            .map { response ->
                if (response.id().isBlank() || response.shards().failed() != 0) {
                    throw IllegalStateException("Snapshot verification PIT is incomplete.")
                }
                PitLease(response.id())
            }

    private fun inspectPages(
        lease: PitLease,
        accumulator: SnapshotCanonicalChecksumAccumulator,
    ): Mono<Long> {
        fun inspectPage(afterIdentity: String?, rootTotal: Long?, consumed: Long): Mono<Long> =
            client.search(searchRequest(lease, afterIdentity), Map::class.java)
                .switchIfEmpty(Mono.error(IllegalStateException("Snapshot verification search returned no response.")))
                .flatMap { response ->
                    val currentTotal = validateResponse(response)
                    val exactRootTotal = rootTotal ?: currentTotal
                    check(currentTotal == exactRootTotal - consumed) {
                        "Snapshot verification keyset total is not stable and exact."
                    }
                    response.pitId()?.takeIf(String::isNotBlank)?.let(lease::update)
                    val hits = response.hits().hits()
                    hits.forEach { hit ->
                        if (hit.ignored().isNotEmpty()) {
                            throw IllegalStateException("Snapshot verification hit contains ignored fields.")
                        }
                        val identity = hit.id()
                            ?: throw IllegalStateException("Snapshot verification hit has no identity.")
                        val document = hit.source()
                            ?: throw IllegalStateException("Snapshot verification hit has no source.")
                        accumulator.accept(identity, document)
                    }
                    val nextConsumed = Math.addExact(consumed, hits.size.toLong())
                    if (hits.size < options.pageSize) {
                        Mono.just(exactRootTotal)
                    } else {
                        val nextIdentity = hits.last().id()
                            ?: return@flatMap Mono.error(
                                IllegalStateException("Snapshot verification page has no terminal identity."),
                            )
                        inspectPage(nextIdentity, exactRootTotal, nextConsumed)
                    }
                }
        return inspectPage(null, null, 0)
    }

    private fun searchRequest(lease: PitLease, afterIdentity: String?): SearchRequest =
        SearchRequest.of { request ->
            request.pit { pit -> pit.id(lease.id).keepAlive { keepAlive -> keepAlive.time(options.pitKeepAlive) } }
                .size(options.pageSize)
                .allowPartialSearchResults(false)
                .trackTotalHits { total -> total.enabled(true) }
                .sort { sort ->
                    sort.field { field -> field.field(MessageRecords.AGGREGATE_ID).order(SortOrder.Asc) }
                }.also { builder ->
                    afterIdentity?.let { identity ->
                        builder.query(
                            range { range ->
                                range.untyped { untyped ->
                                    untyped.field(MessageRecords.AGGREGATE_ID).gt(JsonData.of(identity))
                                }
                            },
                        )
                    }
                }
        }

    private fun validateResponse(response: ResponseBody<*>): Long {
        check(!response.timedOut() && response.shards().failed() == 0) {
            "Snapshot verification search is incomplete."
        }
        val total = checkNotNull(response.hits().total()) {
            "Snapshot verification search has no exact total."
        }
        check(total.relation() == TotalHitsRelation.Eq) { "Snapshot verification search total is not exact." }
        return total.value()
    }

    private fun closePit(lease: PitLease): Mono<Void> = client.closePointInTime(
        ClosePointInTimeRequest.of { request -> request.id(lease.id) },
    ).switchIfEmpty(Mono.error(IllegalStateException("Snapshot verification PIT close returned no response.")))
        .flatMap { response ->
            if (!response.succeeded()) {
                Mono.error(IllegalStateException("Snapshot verification PIT was not closed."))
            } else {
                Mono.empty()
            }
        }

    private fun closeAfterError(lease: PitLease, original: Throwable): Mono<Void> =
        closePit(lease).onErrorResume { closeError ->
            original.addSuppressed(closeError)
            Mono.empty()
        }

    private fun closeAfterCancel(lease: PitLease): Mono<Void> = closePit(lease).onErrorResume { Mono.empty() }

    private class PitLease(initialId: String) {
        var id: String = initialId
            private set

        fun update(nextId: String) {
            id = nextId
        }
    }
}

private fun requireSnapshotVerificationContract(manifest: ElasticsearchIndexMigrationManifest) {
    require(manifest.target.documentKind == QueryDocumentKind.SNAPSHOT) {
        "Canonical Snapshot verification requires a Snapshot target."
    }
    require(
        manifest.verificationContract.checksumAlgorithm ==
            ElasticsearchIndexChecksumAlgorithm.CANONICAL_DOCUMENT_SHA256_V1,
    ) {
        "Canonical Snapshot verification received an unsupported checksum algorithm."
    }
}

private fun <T : Any> Mono<T>.mapVerificationErrors(
    manifest: ElasticsearchIndexMigrationManifest,
): Mono<T> = onErrorMap { error ->
    if (error is ElasticsearchIndexLifecycleException) {
        error
    } else {
        ElasticsearchIndexLifecycleException(
            ElasticsearchIndexLifecycleErrorCode.VERIFICATION_FAILED,
            manifest.id,
            "Elasticsearch Snapshot verification evidence could not be computed.",
            error,
        )
    }
}
