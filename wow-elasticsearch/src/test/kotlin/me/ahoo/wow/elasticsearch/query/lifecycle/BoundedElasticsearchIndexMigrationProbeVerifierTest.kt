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

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.backend.SchemaContractId
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryTarget
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.function.Consumer

class BoundedElasticsearchIndexMigrationProbeVerifierTest {
    @Test
    fun `should compare every registered record and analytics probe against the exact generation`() {
        val recordEqual = probe("record-equal", ElasticsearchIndexProbeKind.RECORD)
        val recordDifferent = probe("record-different", ElasticsearchIndexProbeKind.RECORD)
        val analyticsDifferent = probe("analytics-different", ElasticsearchIndexProbeKind.ANALYTICS)
        val suite = suite(listOf(recordEqual, recordDifferent, analyticsDifferent))
        val executor = ProbeExecutor(
            authority = mapOf(
                recordEqual.id to evidence("1"),
                recordDifferent.id to evidence("2"),
                analyticsDifferent.id to evidence("3"),
            ),
            physical = mapOf(
                recordEqual.id to evidence("1"),
                recordDifferent.id to evidence("4"),
                analyticsDifferent.id to evidence("5"),
            ),
        )

        val result = BoundedElasticsearchIndexMigrationProbeVerifier(suite, executor)
            .compare(COMMAND, MANIFEST, MANIFEST.names.physical)
            .block()!!

        result.assert().isEqualTo(ElasticsearchIndexProbeVerification(1, 1))
        executor.authorityCalls.assert().containsExactly(*suite.probes.toTypedArray())
        executor.physicalCalls.map { it.first }.assert().containsExactly(*suite.probes.toTypedArray())
        executor.physicalCalls.map { it.second }.assert().containsExactly(
            MANIFEST.names.physical,
            MANIFEST.names.physical,
            MANIFEST.names.physical,
        )
    }

    @Test
    fun `suite should be canonical immutable and reject duplicate or excessive definitions`() {
        val source = mutableListOf(
            probe("z-record", ElasticsearchIndexProbeKind.RECORD),
            probe("a-analytics", ElasticsearchIndexProbeKind.ANALYTICS),
        )
        val suite = suite(source)
        source.clear()

        suite.probes.map { it.id.value }.assert().containsExactly("a-analytics", "z-record")
        assertThrownBy<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (suite.probes as MutableList<ElasticsearchIndexMigrationProbe>).clear()
        }
        assertThrownBy<IllegalArgumentException> {
            suite(
                listOf(
                    probe("duplicate", ElasticsearchIndexProbeKind.RECORD),
                    probe("duplicate", ElasticsearchIndexProbeKind.ANALYTICS),
                ),
            )
        }
        assertThrownBy<IllegalArgumentException> {
            suite(
                (0..MAX_ELASTICSEARCH_INDEX_MIGRATION_PROBES).map { index ->
                    probe("probe-$index", ElasticsearchIndexProbeKind.RECORD)
                },
            )
        }
    }

    @Test
    fun `missing incomplete or failed evidence should fail verification without running later probes`() {
        val first = probe("first", ElasticsearchIndexProbeKind.RECORD)
        val later = probe("later", ElasticsearchIndexProbeKind.ANALYTICS)
        val suite = suite(listOf(first, later))
        listOf(
            ProbeExecutor(authority = emptyMap(), physical = mapOf(first.id to evidence("1"))),
            ProbeExecutor(
                authority = mapOf(first.id to evidence("1", complete = false)),
                physical = mapOf(first.id to evidence("1")),
            ),
            ProbeExecutor(
                authority = mapOf(first.id to evidence("1")),
                physical = mapOf(first.id to evidence("1")),
                failure = IllegalStateException("probe failed"),
            ),
            ProbeExecutor(
                authority = mapOf(first.id to evidence("1")),
                physical = mapOf(first.id to evidence("1")),
                failure = ElasticsearchIndexLifecycleException(
                    ElasticsearchIndexLifecycleErrorCode.VALIDATION_FAILED,
                    MANIFEST.id,
                    "forged typed error",
                ),
            ),
        ).forEach { executor ->
            assertThrownBy<ElasticsearchIndexLifecycleException> {
                BoundedElasticsearchIndexMigrationProbeVerifier(suite, executor)
                    .compare(COMMAND, MANIFEST, MANIFEST.names.physical)
                    .block()
            }.satisfies(
                Consumer { error ->
                    error.code.assert().isEqualTo(ElasticsearchIndexLifecycleErrorCode.VERIFICATION_FAILED)
                },
            )
            executor.authorityCalls.assert().containsExactly(first)
            executor.authorityCalls.assert().doesNotContain(later)
        }
    }

    @Test
    fun `suite target schema or id mismatch should fail before any probe executes`() {
        val probe = probe("record", ElasticsearchIndexProbeKind.RECORD)
        listOf(
            suite(listOf(probe), id = ElasticsearchIndexProbeSuiteId("another-suite-v1")),
            suite(
                listOf(probe),
                target = QueryTarget(MaterializedNamedAggregate("sales", "cart"), QueryDocumentKind.SNAPSHOT),
            ),
            suite(listOf(probe), schemaContractId = SchemaContractId("9".repeat(64))),
        ).forEach { mismatchedSuite ->
            val executor = ProbeExecutor(mapOf(probe.id to evidence("1")), mapOf(probe.id to evidence("1")))
            val verifier = BoundedElasticsearchIndexMigrationProbeVerifier(mismatchedSuite, executor)

            assertThrownBy<ElasticsearchIndexLifecycleException> {
                verifier.compare(COMMAND, MANIFEST, MANIFEST.names.physical).block()
            }.satisfies(
                Consumer { error ->
                    error.code.assert().isEqualTo(ElasticsearchIndexLifecycleErrorCode.VALIDATION_FAILED)
                },
            )
            executor.authorityCalls.assert().isEmpty()
            executor.physicalCalls.assert().isEmpty()
        }
    }

    private fun suite(
        probes: Collection<ElasticsearchIndexMigrationProbe>,
        id: ElasticsearchIndexProbeSuiteId = MANIFEST.verificationContract.probeSuiteId,
        target: QueryTarget = MANIFEST.target,
        schemaContractId: SchemaContractId = MANIFEST.schemaContractId,
    ) = ElasticsearchIndexMigrationProbeSuite(id, target, schemaContractId, probes)

    private class ProbeExecutor(
        private val authority: Map<ElasticsearchIndexProbeId, ElasticsearchIndexProbeEvidence>,
        private val physical: Map<ElasticsearchIndexProbeId, ElasticsearchIndexProbeEvidence>,
        private val failure: RuntimeException? = null,
    ) : ElasticsearchIndexMigrationProbeExecutor {
        val authorityCalls = mutableListOf<ElasticsearchIndexMigrationProbe>()
        val physicalCalls = mutableListOf<Pair<ElasticsearchIndexMigrationProbe, ElasticsearchPhysicalIndex>>()

        override fun evaluateAuthority(
            command: ElasticsearchIndexLifecycleCommandId,
            manifest: ElasticsearchIndexMigrationManifest,
            probe: ElasticsearchIndexMigrationProbe,
        ): Mono<ElasticsearchIndexProbeEvidence> = Mono.defer {
            authorityCalls += probe
            Mono.justOrEmpty(authority[probe.id])
        }

        override fun evaluatePhysical(
            command: ElasticsearchIndexLifecycleCommandId,
            manifest: ElasticsearchIndexMigrationManifest,
            physicalIndex: ElasticsearchPhysicalIndex,
            probe: ElasticsearchIndexMigrationProbe,
        ): Mono<ElasticsearchIndexProbeEvidence> = Mono.defer {
            physicalCalls += probe to physicalIndex
            failure?.let { throw it }
            Mono.justOrEmpty(physical[probe.id])
        }
    }

    private companion object {
        val MANIFEST = ElasticsearchIndexMigrationManifest(
            ElasticsearchIndexMigrationId("probe-verification"),
            QueryTarget(MaterializedNamedAggregate("sales", "order"), QueryDocumentKind.SNAPSHOT),
            ElasticsearchIndexMappingVersion(2),
            ElasticsearchIndexGeneration(3),
            SchemaContractId("1".repeat(64)),
            ElasticsearchIndexCapabilityDigest("2".repeat(64)),
            ElasticsearchPhysicalIndex("wow.sales.order.snapshot-v0001-000001"),
            ElasticsearchIndexRebuildStrategy.SNAPSHOT_FROM_EVENT_STREAM,
            testVerificationContract(),
            Duration.ofMinutes(5),
            Duration.ofHours(1),
        )
        val COMMAND = ElasticsearchIndexLifecycleCommandId("probe-command")
    }
}

private fun probe(id: String, kind: ElasticsearchIndexProbeKind) = ElasticsearchIndexMigrationProbe(
    ElasticsearchIndexProbeId(id),
    kind,
)

private fun evidence(value: String, complete: Boolean = true) = ElasticsearchIndexProbeEvidence(
    resultCount = 1,
    resultChecksum = ElasticsearchIndexChecksum(value.repeat(64)),
    complete = complete,
)
