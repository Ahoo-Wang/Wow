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

@file:OptIn(
    me.ahoo.wow.query.backend.ExperimentalQueryBackendApi::class,
    me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class,
)

package me.ahoo.wow.elasticsearch.query.lifecycle

import me.ahoo.wow.query.backend.SchemaContractId
import me.ahoo.wow.query.gateway.QueryTarget
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.Collections

internal const val MAX_ELASTICSEARCH_INDEX_MIGRATION_PROBES = 256

@JvmInline
internal value class ElasticsearchIndexProbeId(val value: String) {
    init {
        require(value.matches(PROBE_ID_PATTERN)) {
            "Elasticsearch index probe id must match ${PROBE_ID_PATTERN.pattern}."
        }
    }
}

internal enum class ElasticsearchIndexProbeKind {
    RECORD,
    ANALYTICS,
}

internal data class ElasticsearchIndexMigrationProbe(
    val id: ElasticsearchIndexProbeId,
    val kind: ElasticsearchIndexProbeKind,
)

/** Immutable, canonical suite of application-owned semantic probes. */
internal class ElasticsearchIndexMigrationProbeSuite(
    val id: ElasticsearchIndexProbeSuiteId,
    val target: QueryTarget,
    val schemaContractId: SchemaContractId,
    probes: Collection<ElasticsearchIndexMigrationProbe>,
) {
    val probes: List<ElasticsearchIndexMigrationProbe>

    init {
        val snapshot = probes.toList()
        require(snapshot.isNotEmpty()) { "Elasticsearch index migration probe suite must not be empty." }
        require(snapshot.size <= MAX_ELASTICSEARCH_INDEX_MIGRATION_PROBES) {
            "Elasticsearch index migration probe suite exceeds its probe budget."
        }
        require(snapshot.map(ElasticsearchIndexMigrationProbe::id).distinct().size == snapshot.size) {
            "Elasticsearch index migration probe ids must be unique."
        }
        this.probes = Collections.unmodifiableList(snapshot.sortedBy { probe -> probe.id.value })
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ElasticsearchIndexMigrationProbeSuite &&
            id == other.id &&
            target == other.target &&
            schemaContractId == other.schemaContractId &&
            probes == other.probes

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + target.hashCode()
        result = 31 * result + schemaContractId.hashCode()
        result = 31 * result + probes.hashCode()
        return result
    }
}

/** Backend-neutral fingerprint of one fully materialized probe result. */
internal data class ElasticsearchIndexProbeEvidence(
    val resultCount: Long,
    val resultChecksum: ElasticsearchIndexChecksum,
    val complete: Boolean,
) {
    init {
        require(resultCount >= 0) { "Elasticsearch index probe result count must not be negative." }
    }
}

/** Executes pre-registered probes without exposing wire queries or driver objects to the lifecycle kernel. */
internal interface ElasticsearchIndexMigrationProbeExecutor {
    fun evaluateAuthority(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
        probe: ElasticsearchIndexMigrationProbe,
    ): Mono<ElasticsearchIndexProbeEvidence>

    fun evaluatePhysical(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
        physicalIndex: ElasticsearchPhysicalIndex,
        probe: ElasticsearchIndexMigrationProbe,
    ): Mono<ElasticsearchIndexProbeEvidence>
}

/** Compares a bounded suite against authority and one exact physical generation, once per subscription. */
internal class BoundedElasticsearchIndexMigrationProbeVerifier(
    private val suite: ElasticsearchIndexMigrationProbeSuite,
    private val executor: ElasticsearchIndexMigrationProbeExecutor,
) : ElasticsearchIndexMigrationProbeVerifier {
    override val suiteId: ElasticsearchIndexProbeSuiteId
        get() = suite.id

    override fun compare(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
        physicalIndex: ElasticsearchPhysicalIndex,
    ): Mono<ElasticsearchIndexProbeVerification> = Mono.defer {
        if (
            suite.id != manifest.verificationContract.probeSuiteId ||
            suite.target != manifest.target ||
            suite.schemaContractId != manifest.schemaContractId
        ) {
            reject(
                ElasticsearchIndexLifecycleErrorCode.VALIDATION_FAILED,
                manifest.id,
                "Elasticsearch query probe suite does not match the migration target and verification contract.",
            )
        }
        Flux.fromIterable(suite.probes)
            .concatMap { probe -> compareProbe(command, manifest, physicalIndex, probe) }
            .reduce(ElasticsearchIndexProbeVerification(0, 0), ::accumulate)
    }.onErrorMap { error -> normalizeProbeError(manifest, error) }

    private fun compareProbe(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
        physicalIndex: ElasticsearchPhysicalIndex,
        probe: ElasticsearchIndexMigrationProbe,
    ): Mono<ProbeComparison> = Mono.zip(
        Mono.defer { executor.evaluateAuthority(command, manifest, probe) }
            .normalizeExecutorError(manifest, probe, "authority")
            .requireEvidence(manifest, probe, "authority"),
        Mono.defer { executor.evaluatePhysical(command, manifest, physicalIndex, probe) }
            .normalizeExecutorError(manifest, probe, "physical generation")
            .requireEvidence(manifest, probe, "physical generation"),
    ).map { tuple -> ProbeComparison(probe.kind, tuple.t1 != tuple.t2) }

    private fun Mono<ElasticsearchIndexProbeEvidence>.normalizeExecutorError(
        manifest: ElasticsearchIndexMigrationManifest,
        probe: ElasticsearchIndexMigrationProbe,
        source: String,
    ): Mono<ElasticsearchIndexProbeEvidence> = onErrorMap { error ->
        ElasticsearchIndexLifecycleException(
            ElasticsearchIndexLifecycleErrorCode.VERIFICATION_FAILED,
            manifest.id,
            "Elasticsearch $source failed while evaluating probe [${probe.id.value}].",
            error,
        )
    }

    private fun Mono<ElasticsearchIndexProbeEvidence>.requireEvidence(
        manifest: ElasticsearchIndexMigrationManifest,
        probe: ElasticsearchIndexMigrationProbe,
        source: String,
    ): Mono<ElasticsearchIndexProbeEvidence> = switchIfEmpty(
        Mono.error(
            ElasticsearchIndexLifecycleException(
                ElasticsearchIndexLifecycleErrorCode.VERIFICATION_FAILED,
                manifest.id,
                "Elasticsearch $source returned no evidence for probe [${probe.id.value}].",
            ),
        ),
    ).flatMap { evidence ->
        if (evidence.complete) {
            Mono.just(evidence)
        } else {
            Mono.error(
                ElasticsearchIndexLifecycleException(
                    ElasticsearchIndexLifecycleErrorCode.VERIFICATION_FAILED,
                    manifest.id,
                    "Elasticsearch $source returned incomplete evidence for probe [${probe.id.value}].",
                ),
            )
        }
    }

    private fun accumulate(
        current: ElasticsearchIndexProbeVerification,
        comparison: ProbeComparison,
    ): ElasticsearchIndexProbeVerification {
        if (!comparison.mismatch) {
            return current
        }
        return when (comparison.kind) {
            ElasticsearchIndexProbeKind.RECORD -> current.copy(
                recordMismatchCount = Math.addExact(current.recordMismatchCount, 1),
            )

            ElasticsearchIndexProbeKind.ANALYTICS -> current.copy(
                analyticsMismatchCount = Math.addExact(current.analyticsMismatchCount, 1),
            )
        }
    }
}

private data class ProbeComparison(
    val kind: ElasticsearchIndexProbeKind,
    val mismatch: Boolean,
)

private fun normalizeProbeError(
    manifest: ElasticsearchIndexMigrationManifest,
    error: Throwable,
): Throwable = if (error is ElasticsearchIndexLifecycleException) {
    error
} else {
    ElasticsearchIndexLifecycleException(
        ElasticsearchIndexLifecycleErrorCode.VERIFICATION_FAILED,
        manifest.id,
        "Elasticsearch query probe evidence could not be computed.",
        error,
    )
}

private val PROBE_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
