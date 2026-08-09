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
import java.time.Duration
import java.time.Instant
import java.util.function.Consumer

class ElasticsearchIndexLifecycleModelTest {
    @Test
    fun `names should retain the stable alias and encode mapping version plus generation`() {
        val snapshot = ElasticsearchIndexNames.of(SNAPSHOT_TARGET, VERSION, GENERATION)
        val eventStream = ElasticsearchIndexNames.of(EVENT_TARGET, VERSION, GENERATION)

        snapshot.alias.value.assert().isEqualTo("wow.sales.order.snapshot")
        snapshot.physical.value.assert().isEqualTo("wow.sales.order.snapshot-v0002-000007")
        eventStream.alias.value.assert().isEqualTo("wow.sales.order.es")
        eventStream.physical.value.assert().isEqualTo("wow.sales.order.es-v0002-000007")
    }

    @Test
    fun `verification checksum algorithm must match the document kind`() {
        assertThrownBy<IllegalArgumentException> {
            eventManifest().copy(verificationContract = testVerificationContract())
        }
        assertThrownBy<IllegalArgumentException> {
            manifest().copy(verificationContract = testEventStreamVerificationContract())
        }
    }

    @Test
    fun `validation should bind the exact alias source and destination attestation`() {
        val manifest = manifest()
        val expectedDestination = manifest.destinationAttestation
        manifest.validate(
            ElasticsearchIndexInventory(
                manifest.names.alias,
                SOURCE,
                mapOf(SOURCE to sourceAttestation(), manifest.names.physical to expectedDestination),
                OBSERVED_AT,
            ),
        )

        assertLifecycle(ElasticsearchIndexLifecycleErrorCode.ALIAS_CONFLICT) {
            val drifted = ElasticsearchPhysicalIndex("wow.sales.order.snapshot-v0001-000002")
            manifest.validate(
                ElasticsearchIndexInventory(
                    manifest.names.alias,
                    drifted,
                    mapOf(
                        SOURCE to sourceAttestation(),
                        drifted to sourceAttestation().copy(physicalIndex = drifted),
                    ),
                    OBSERVED_AT,
                ),
            )
        }

        assertLifecycle(ElasticsearchIndexLifecycleErrorCode.ATTESTATION_MISMATCH) {
            manifest.validate(
                ElasticsearchIndexInventory(
                    manifest.names.alias,
                    SOURCE,
                    mapOf(
                        SOURCE to sourceAttestation(),
                        manifest.names.physical to expectedDestination.copy(
                            capabilityDigest = ElasticsearchIndexCapabilityDigest("b".repeat(64)),
                        ),
                    ),
                    OBSERVED_AT,
                ),
            )
        }
    }

    @Test
    fun `inventory and evidence should be immutable value objects`() {
        val mutable = linkedMapOf(SOURCE to sourceAttestation())
        val inventory = ElasticsearchIndexInventory(manifest().names.alias, SOURCE, mutable, OBSERVED_AT)
        mutable.clear()

        inventory.indices.keys.assert().containsExactly(SOURCE)
        assertThrownBy<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (inventory.indices as MutableMap).clear()
        }
        inventory.assert().isEqualTo(
            ElasticsearchIndexInventory(
                manifest().names.alias,
                SOURCE,
                mapOf(SOURCE to sourceAttestation()),
                OBSERVED_AT,
            ),
        )
    }

    @Test
    fun `verification must prove exact data and probe equivalence`() {
        verification().requireSatisfied(manifest(), manifest().names.physical)

        assertLifecycle(ElasticsearchIndexLifecycleErrorCode.VERIFICATION_FAILED) {
            verification(actualCount = 9).requireSatisfied(manifest(), manifest().names.physical)
        }

        assertLifecycle(ElasticsearchIndexLifecycleErrorCode.VERIFICATION_FAILED) {
            verification(indexedWatermark = 41).requireSatisfied(manifest(), manifest().names.physical)
        }
    }

    @Test
    fun `event stream rebuild and verification must prove a converged non-null watermark`() {
        val manifest = eventManifest()
        ElasticsearchIndexRebuildReceipt(
            manifest.names.physical,
            manifest.rebuildStrategy,
            authoritativeWatermark = 42,
            indexedWatermark = 42,
            completedAt = OBSERVED_AT,
        ).requireMatches(manifest)
        eventVerification(manifest, 42, 42).requireSatisfied(manifest, manifest.names.physical)

        assertLifecycle(ElasticsearchIndexLifecycleErrorCode.VALIDATION_FAILED) {
            ElasticsearchIndexRebuildReceipt(
                manifest.names.physical,
                manifest.rebuildStrategy,
                authoritativeWatermark = null,
                indexedWatermark = null,
                completedAt = OBSERVED_AT,
            ).requireMatches(manifest)
        }
        assertLifecycle(ElasticsearchIndexLifecycleErrorCode.VALIDATION_FAILED) {
            ElasticsearchIndexRebuildReceipt(
                manifest.names.physical,
                manifest.rebuildStrategy,
                authoritativeWatermark = 42,
                indexedWatermark = 41,
                completedAt = OBSERVED_AT,
            ).requireMatches(manifest)
        }
        assertLifecycle(ElasticsearchIndexLifecycleErrorCode.VERIFICATION_FAILED) {
            eventVerification(manifest, null, null).requireSatisfied(manifest, manifest.names.physical)
        }
    }

    private fun assertLifecycle(
        code: ElasticsearchIndexLifecycleErrorCode,
        action: () -> Unit,
    ) {
        assertThrownBy<ElasticsearchIndexLifecycleException>(action).satisfies(
            Consumer { error -> error.code.assert().isEqualTo(code) },
        )
    }

    private fun manifest() = ElasticsearchIndexMigrationManifest(
        ElasticsearchIndexMigrationId("sales-order-snapshot-v2-g7"),
        SNAPSHOT_TARGET,
        VERSION,
        GENERATION,
        SchemaContractId("1".repeat(64)),
        ElasticsearchIndexCapabilityDigest("a".repeat(64)),
        SOURCE,
        ElasticsearchIndexRebuildStrategy.SNAPSHOT_FROM_EVENT_STREAM,
        testVerificationContract(),
        Duration.ofMinutes(5),
        Duration.ofHours(1),
    )

    private fun eventManifest() = ElasticsearchIndexMigrationManifest(
        ElasticsearchIndexMigrationId("sales-order-event-stream-v2-g7"),
        EVENT_TARGET,
        VERSION,
        GENERATION,
        SchemaContractId("1".repeat(64)),
        ElasticsearchIndexCapabilityDigest("a".repeat(64)),
        EVENT_SOURCE,
        ElasticsearchIndexRebuildStrategy.EVENT_STREAM_PAUSE_AND_DRAIN,
        testEventStreamVerificationContract(),
        Duration.ofMinutes(5),
        Duration.ofHours(1),
    )

    private fun sourceAttestation() = ElasticsearchIndexAttestation(
        SOURCE,
        ElasticsearchIndexMappingVersion(1),
        QueryDocumentKind.SNAPSHOT,
        SchemaContractId("0".repeat(64)),
        ElasticsearchIndexCapabilityDigest("c".repeat(64)),
    )

    private fun verification(
        actualCount: Long = 10,
        indexedWatermark: Long = 42,
    ) = ElasticsearchIndexVerification(
        manifest().names.physical,
        expectedCount = 10,
        actualCount = actualCount,
        expectedIdentityChecksum = ElasticsearchIndexChecksum("2".repeat(64)),
        actualIdentityChecksum = ElasticsearchIndexChecksum("2".repeat(64)),
        expectedContentChecksum = ElasticsearchIndexChecksum("3".repeat(64)),
        actualContentChecksum = ElasticsearchIndexChecksum("3".repeat(64)),
        versionContinuity = true,
        authoritativeWatermark = 42,
        indexedWatermark = indexedWatermark,
        recordProbeMismatchCount = 0,
        analyticsProbeMismatchCount = 0,
        verifiedAt = OBSERVED_AT,
    )

    private fun eventVerification(
        manifest: ElasticsearchIndexMigrationManifest,
        authoritativeWatermark: Long?,
        indexedWatermark: Long?,
    ) = ElasticsearchIndexVerification(
        manifest.names.physical,
        expectedCount = 10,
        actualCount = 10,
        expectedIdentityChecksum = ElasticsearchIndexChecksum("2".repeat(64)),
        actualIdentityChecksum = ElasticsearchIndexChecksum("2".repeat(64)),
        expectedContentChecksum = ElasticsearchIndexChecksum("3".repeat(64)),
        actualContentChecksum = ElasticsearchIndexChecksum("3".repeat(64)),
        versionContinuity = true,
        authoritativeWatermark = authoritativeWatermark,
        indexedWatermark = indexedWatermark,
        recordProbeMismatchCount = 0,
        analyticsProbeMismatchCount = 0,
        verifiedAt = OBSERVED_AT,
    )

    private companion object {
        val SNAPSHOT_TARGET = QueryTarget(
            MaterializedNamedAggregate("sales", "order"),
            QueryDocumentKind.SNAPSHOT,
        )
        val EVENT_TARGET = QueryTarget(
            MaterializedNamedAggregate("sales", "order"),
            QueryDocumentKind.EVENT_STREAM,
        )
        val VERSION = ElasticsearchIndexMappingVersion(2)
        val GENERATION = ElasticsearchIndexGeneration(7)
        val SOURCE = ElasticsearchPhysicalIndex("wow.sales.order.snapshot-v0001-000001")
        val EVENT_SOURCE = ElasticsearchPhysicalIndex("wow.sales.order.es-v0001-000001")
        val OBSERVED_AT: Instant = Instant.parse("2026-08-08T01:00:00Z")
    }
}
