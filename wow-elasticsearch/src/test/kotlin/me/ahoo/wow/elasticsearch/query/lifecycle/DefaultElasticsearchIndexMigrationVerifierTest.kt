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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.function.Consumer

class DefaultElasticsearchIndexMigrationVerifierTest {
    @Test
    fun `should combine authority index and query probe evidence without changing its provenance`() {
        val verifier = verifier(
            ElasticsearchAuthoritativeVerificationSnapshot(ALGORITHM, 2, IDENTITY, CONTENT, 7),
            ElasticsearchPhysicalIndexVerificationSnapshot(ALGORITHM, DESTINATION, 2, IDENTITY, CONTENT, true, 7),
            ElasticsearchIndexProbeVerification(0, 0),
        )

        val verification = verifier.verify(COMMAND, MANIFEST, DESTINATION).block()!!

        verification.assert().isEqualTo(
            ElasticsearchIndexVerification(
                DESTINATION,
                2,
                2,
                IDENTITY,
                IDENTITY,
                CONTENT,
                CONTENT,
                true,
                7,
                7,
                0,
                0,
                NOW,
            ),
        )
        verification.requireSatisfied(MANIFEST, DESTINATION)
    }

    @Test
    fun `should preserve a missing index watermark as verification evidence and fail closed`() {
        val verification = verifier(
            ElasticsearchAuthoritativeVerificationSnapshot(ALGORITHM, 2, IDENTITY, CONTENT, 7),
            ElasticsearchPhysicalIndexVerificationSnapshot(ALGORITHM, DESTINATION, 2, IDENTITY, CONTENT, true, null),
            ElasticsearchIndexProbeVerification(0, 0),
        ).verify(COMMAND, MANIFEST, DESTINATION).block()!!

        verification.authoritativeWatermark.assert().isEqualTo(7)
        verification.indexedWatermark.assert().isNull()
        assertThrownBy<ElasticsearchIndexLifecycleException> {
            verification.requireSatisfied(MANIFEST, DESTINATION)
        }.satisfies(
            Consumer { error ->
                error.code.assert().isEqualTo(ElasticsearchIndexLifecycleErrorCode.VERIFICATION_FAILED)
            },
        )
    }

    @Test
    fun `should reject a probe suite that is not pinned by the migration manifest`() {
        val mismatched = MANIFEST.copy(
            verificationContract = MANIFEST.verificationContract.copy(
                probeSuiteId = ElasticsearchIndexProbeSuiteId("another-probe-suite-v1"),
            ),
        )

        assertThrownBy<ElasticsearchIndexLifecycleException> {
            verifier(
                ElasticsearchAuthoritativeVerificationSnapshot(ALGORITHM, 0, IDENTITY, CONTENT, null),
                ElasticsearchPhysicalIndexVerificationSnapshot(
                    ALGORITHM,
                    DESTINATION,
                    0,
                    IDENTITY,
                    CONTENT,
                    true,
                    null,
                ),
                ElasticsearchIndexProbeVerification(0, 0),
            ).verify(COMMAND, mismatched, DESTINATION).block()
        }.satisfies(
            Consumer { error ->
                error.code.assert().isEqualTo(ElasticsearchIndexLifecycleErrorCode.VALIDATION_FAILED)
                error.migrationId.assert().isEqualTo(MANIFEST.id)
            },
        )
    }

    private fun verifier(
        expected: ElasticsearchAuthoritativeVerificationSnapshot,
        actual: ElasticsearchPhysicalIndexVerificationSnapshot,
        probes: ElasticsearchIndexProbeVerification,
    ) = DefaultElasticsearchIndexMigrationVerifier(
        ElasticsearchAuthoritativeVerificationSource { command, manifest ->
            command.assert().isEqualTo(COMMAND)
            manifest.assert().isEqualTo(MANIFEST)
            Mono.just(expected)
        },
        ElasticsearchPhysicalIndexVerificationSource { command, manifest, physical ->
            command.assert().isEqualTo(COMMAND)
            manifest.assert().isEqualTo(MANIFEST)
            physical.assert().isEqualTo(DESTINATION)
            Mono.just(actual)
        },
        object : ElasticsearchIndexMigrationProbeVerifier {
            override val suiteId = ElasticsearchIndexProbeSuiteId("test-probes-v1")

            override fun compare(
                command: ElasticsearchIndexLifecycleCommandId,
                manifest: ElasticsearchIndexMigrationManifest,
                physicalIndex: ElasticsearchPhysicalIndex,
            ): Mono<ElasticsearchIndexProbeVerification> {
                command.assert().isEqualTo(COMMAND)
                manifest.assert().isEqualTo(MANIFEST)
                physicalIndex.assert().isEqualTo(DESTINATION)
                return Mono.just(probes)
            }
        },
        CLOCK,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-08T01:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
        val TARGET = QueryTarget(
            MaterializedNamedAggregate("sales", "order"),
            QueryDocumentKind.SNAPSHOT,
        )
        val SOURCE = ElasticsearchPhysicalIndex("wow.sales.order.snapshot-v0001-000001")
        val DESTINATION = ElasticsearchPhysicalIndex("wow.sales.order.snapshot-v0002-000002")
        val IDENTITY = ElasticsearchIndexChecksum("1".repeat(64))
        val CONTENT = ElasticsearchIndexChecksum("2".repeat(64))
        val ALGORITHM = ElasticsearchIndexChecksumAlgorithm.CANONICAL_DOCUMENT_SHA256_V1
        val COMMAND = ElasticsearchIndexLifecycleCommandId("verify-command")
        val MANIFEST = ElasticsearchIndexMigrationManifest(
            ElasticsearchIndexMigrationId("snapshot-verification"),
            TARGET,
            ElasticsearchIndexMappingVersion(2),
            ElasticsearchIndexGeneration(2),
            SchemaContractId("3".repeat(64)),
            ElasticsearchIndexCapabilityDigest("4".repeat(64)),
            SOURCE,
            ElasticsearchIndexRebuildStrategy.SNAPSHOT_FROM_EVENT_STREAM,
            testVerificationContract(),
            Duration.ofMinutes(5),
            Duration.ofHours(1),
        )
    }
}
