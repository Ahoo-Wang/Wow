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
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant

class ElasticsearchIndexLifecycleStateCodecTest {
    private val codec = ElasticsearchIndexLifecycleStateCodec()

    @Test
    fun `should round trip every persisted lifecycle field`() {
        val state = completeState()

        val decoded = codec.decode(MIGRATION_ID, codec.encode(state))

        decoded.assert().isEqualTo(state)
    }

    @Test
    fun `should reject corrupt mismatched trailing and oversized payloads`() {
        val encoded = codec.encode(ElasticsearchIndexMigrationState.initial(MANIFEST))
        val corrupt = encoded.copyOf().also { it[0] = (it[0].toInt() xor 0x7f).toByte() }
        val versionOne = encoded.copyOf().also { ByteBuffer.wrap(it).putInt(Int.SIZE_BYTES, 1) }

        assertCorrupt { codec.decode(MIGRATION_ID, corrupt) }
        assertCorrupt { codec.decode(MIGRATION_ID, versionOne) }
        assertCorrupt { codec.decode(ElasticsearchIndexMigrationId("another-migration"), encoded) }
        assertCorrupt { codec.decode(MIGRATION_ID, encoded + 0) }
        assertCorrupt {
            codec.decode(
                MIGRATION_ID,
                ByteArray(ElasticsearchIndexLifecycleStateCodec.MAX_PAYLOAD_BYTES + 1),
            )
        }
    }

    private fun completeState(): ElasticsearchIndexMigrationState {
        val sourceAttestation = ElasticsearchIndexAttestation(
            SOURCE,
            ElasticsearchIndexMappingVersion(1),
            QueryDocumentKind.SNAPSHOT,
            SchemaContractId("0".repeat(64)),
            ElasticsearchIndexCapabilityDigest("c".repeat(64)),
        )
        val destinationAttestation = MANIFEST.destinationAttestation
        val inventory = ElasticsearchIndexInventory(
            MANIFEST.names.alias,
            SOURCE,
            linkedMapOf(SOURCE to sourceAttestation, DESTINATION to destinationAttestation),
            NOW.minusSeconds(60),
        )
        val rebuild = ElasticsearchIndexRebuildReceipt(
            DESTINATION,
            ElasticsearchIndexRebuildStrategy.SNAPSHOT_FROM_EVENT_STREAM,
            authoritativeWatermark = 123,
            indexedWatermark = 123,
            completedAt = NOW.minusSeconds(40),
        )
        val destinationVerification = verification(DESTINATION, NOW.minusSeconds(30))
        val rollbackVerification = verification(SOURCE, NOW.minusSeconds(10))
        return ElasticsearchIndexMigrationState(
            manifest = MANIFEST,
            phase = ElasticsearchIndexLifecyclePhase.ROLLBACK_VERIFIED,
            revision = 12,
            activeCommand = ElasticsearchIndexActiveCommand(
                ElasticsearchIndexLifecycleCommandId("rollback-command"),
                ElasticsearchIndexLifecycleCommandType.ROLLBACK,
                ElasticsearchIndexLifecyclePhase.ROLLBACK_VERIFIED,
                ElasticsearchIndexLifecyclePhase.ROLLED_BACK,
                NOW,
            ),
            lastCompletedCommand = ElasticsearchIndexCompletedCommand(
                ElasticsearchIndexLifecycleCommandId("verify-rollback-command"),
                ElasticsearchIndexLifecycleCommandType.VERIFY,
                NOW.minusSeconds(5),
            ),
            inventory = inventory,
            destinationAttestation = destinationAttestation,
            rebuildReceipt = rebuild,
            destinationVerification = destinationVerification,
            cutover = ElasticsearchAliasTransition(MANIFEST.names.alias, SOURCE, DESTINATION, NOW.minusSeconds(20)),
            rollbackVerification = rollbackVerification,
            rollback = ElasticsearchAliasTransition(MANIFEST.names.alias, DESTINATION, SOURCE, NOW.plusSeconds(10)),
            retainedSourceUntil = NOW.plus(MANIFEST.minimumRetention),
        )
    }

    private fun verification(index: ElasticsearchPhysicalIndex, verifiedAt: Instant) =
        ElasticsearchIndexVerification(
            index,
            expectedCount = 10,
            actualCount = 10,
            expectedIdentityChecksum = ElasticsearchIndexChecksum("1".repeat(64)),
            actualIdentityChecksum = ElasticsearchIndexChecksum("1".repeat(64)),
            expectedContentChecksum = ElasticsearchIndexChecksum("2".repeat(64)),
            actualContentChecksum = ElasticsearchIndexChecksum("2".repeat(64)),
            versionContinuity = true,
            authoritativeWatermark = 123,
            indexedWatermark = 123,
            recordProbeMismatchCount = 0,
            analyticsProbeMismatchCount = 0,
            verifiedAt = verifiedAt,
        )

    private fun assertCorrupt(action: () -> Unit) {
        assertThrownBy<ElasticsearchIndexLifecycleException>(action).satisfies(
            java.util.function.Consumer { error ->
                error.code.assert().isEqualTo(ElasticsearchIndexLifecycleErrorCode.REPOSITORY_CORRUPTED)
            },
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-08T04:00:00Z")
        val MIGRATION_ID = ElasticsearchIndexMigrationId("lifecycle-order-snapshot-v2-g7")
        val TARGET = QueryTarget(MaterializedNamedAggregate("lifecycle", "order"), QueryDocumentKind.SNAPSHOT)
        val SOURCE = ElasticsearchPhysicalIndex("wow.lifecycle.order.snapshot-v0001-000001")
        val DESTINATION = ElasticsearchPhysicalIndex("wow.lifecycle.order.snapshot-v0002-000007")
        val MANIFEST = ElasticsearchIndexMigrationManifest(
            MIGRATION_ID,
            TARGET,
            ElasticsearchIndexMappingVersion(2),
            ElasticsearchIndexGeneration(7),
            SchemaContractId("1".repeat(64)),
            ElasticsearchIndexCapabilityDigest("a".repeat(64)),
            SOURCE,
            ElasticsearchIndexRebuildStrategy.SNAPSHOT_FROM_EVENT_STREAM,
            testVerificationContract(),
            Duration.ofMinutes(5),
            Duration.ofHours(1),
        )
    }
}
