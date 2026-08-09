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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

class ElasticsearchIndexLifecycleExecutorTest {
    @Test
    fun `commands should follow the explicit verified cutover and fresh rollback verification sequence`() {
        val fixture = fixture()
        var state = fixture.executor.register(MANIFEST).block()!!
        state.phase.assert().isEqualTo(ElasticsearchIndexLifecyclePhase.NEW)

        state = fixture.execute(state, ElasticsearchIndexLifecycleCommandType.VALIDATE)
        state = fixture.execute(state, ElasticsearchIndexLifecycleCommandType.CREATE)
        state = fixture.execute(state, ElasticsearchIndexLifecycleCommandType.REBUILD)
        state = fixture.execute(state, ElasticsearchIndexLifecycleCommandType.VERIFY)
        state.phase.assert().isEqualTo(ElasticsearchIndexLifecyclePhase.VERIFIED)
        state = fixture.execute(state, ElasticsearchIndexLifecycleCommandType.CUTOVER)
        state.phase.assert().isEqualTo(ElasticsearchIndexLifecyclePhase.CUTOVER)
        state.retainedSourceUntil.assert().isEqualTo(NOW.plus(MANIFEST.minimumRetention))

        state = fixture.execute(state, ElasticsearchIndexLifecycleCommandType.VERIFY)
        state.rollbackVerification!!.physicalIndex.assert().isEqualTo(SOURCE)
        state.phase.assert().isEqualTo(ElasticsearchIndexLifecyclePhase.ROLLBACK_VERIFIED)
        state = fixture.execute(state, ElasticsearchIndexLifecycleCommandType.ROLLBACK)

        state.phase.assert().isEqualTo(ElasticsearchIndexLifecyclePhase.ROLLED_BACK)
        fixture.operations.calls.assert().containsExactly(
            "validate",
            "create",
            "rebuild",
            "verify:${DESTINATION.value}",
            "cutover",
            "verify:${SOURCE.value}",
            "rollback",
        )
    }

    @Test
    fun `invalid order and failed verification should fail before alias mutation`() {
        val fixture = fixture()
        val initial = fixture.executor.register(MANIFEST).block()!!

        assertLifecycle(ElasticsearchIndexLifecycleErrorCode.INVALID_TRANSITION) {
            fixture.execute(initial, ElasticsearchIndexLifecycleCommandType.CUTOVER)
        }
        fixture.operations.calls.assert().isEmpty()

        var state = fixture.execute(initial, ElasticsearchIndexLifecycleCommandType.VALIDATE)
        state = fixture.execute(state, ElasticsearchIndexLifecycleCommandType.CREATE)
        state = fixture.execute(state, ElasticsearchIndexLifecycleCommandType.REBUILD)
        fixture.operations.verificationMatches.set(false)
        assertLifecycle(ElasticsearchIndexLifecycleErrorCode.VERIFICATION_FAILED) {
            fixture.execute(state, ElasticsearchIndexLifecycleCommandType.VERIFY)
        }
        fixture.operations.calls.assert().doesNotContain("cutover")
    }

    @Test
    fun `snapshot cutover without a trusted write fence should fail before alias mutation`() {
        val fixture = fixture(ElasticsearchSnapshotCutoverGuard.DENY)
        var state = fixture.executor.register(MANIFEST).block()!!
        state = fixture.execute(state, ElasticsearchIndexLifecycleCommandType.VALIDATE)
        state = fixture.execute(state, ElasticsearchIndexLifecycleCommandType.CREATE)
        state = fixture.execute(state, ElasticsearchIndexLifecycleCommandType.REBUILD)
        state = fixture.execute(state, ElasticsearchIndexLifecycleCommandType.VERIFY)

        assertLifecycle(ElasticsearchIndexLifecycleErrorCode.CUTOVER_FENCE_REQUIRED) {
            fixture.execute(state, ElasticsearchIndexLifecycleCommandType.CUTOVER)
        }
        fixture.operations.calls.assert().doesNotContain("cutover")
    }

    @Test
    fun `same command should resume idempotently while a different command is rejected`() {
        val fixture = fixture()
        val initial = fixture.executor.register(MANIFEST).block()!!
        fixture.operations.failValidationOnce.set(true)
        val command = command(initial, ElasticsearchIndexLifecycleCommandType.VALIDATE, "validate-1")

        assertLifecycle(ElasticsearchIndexLifecycleErrorCode.OPERATION_FAILED) {
            fixture.executor.execute(command).block()
        }
        val claimed = fixture.repository.get(MANIFEST.id)!!
        claimed.activeCommand!!.id.assert().isEqualTo(command.id)

        assertLifecycle(ElasticsearchIndexLifecycleErrorCode.COMMAND_CONFLICT) {
            fixture.executor.execute(
                command(initial, ElasticsearchIndexLifecycleCommandType.VALIDATE, "validate-2"),
            ).block()
        }

        val resumed = fixture.executor.execute(command).block()!!
        resumed.phase.assert().isEqualTo(ElasticsearchIndexLifecyclePhase.VALIDATED)
        resumed.lastCompletedCommand!!.id.assert().isEqualTo(command.id)
        fixture.operations.validateCalls.get().assert().isEqualTo(2)

        fixture.executor.execute(command).block()!!.assert().isEqualTo(resumed)
        fixture.operations.validateCalls.get().assert().isEqualTo(2)
    }

    @Test
    fun `dry run should be read only and describe the transition`() {
        val fixture = fixture()
        val initial = fixture.executor.register(MANIFEST).block()!!
        val plan = fixture.executor.plan(
            command(initial, ElasticsearchIndexLifecycleCommandType.VALIDATE, "validate-plan"),
        ).block()!!

        plan.from.assert().isEqualTo(ElasticsearchIndexLifecyclePhase.NEW)
        plan.to.assert().isEqualTo(ElasticsearchIndexLifecyclePhase.VALIDATED)
        fixture.repository.get(MANIFEST.id).assert().isEqualTo(initial)
        fixture.operations.calls.assert().isEmpty()
    }

    private fun fixture(
        cutoverGuard: ElasticsearchSnapshotCutoverGuard = ElasticsearchSnapshotCutoverGuard { _, _ -> Mono.empty() },
    ): Fixture {
        val repository = InMemoryElasticsearchIndexLifecycleRepository()
        val operations = ProbeOperations()
        return Fixture(
            repository,
            operations,
            ElasticsearchIndexLifecycleExecutor(
                repository,
                operations,
                Clock.fixed(NOW, ZoneOffset.UTC),
                cutoverGuard,
            ),
        )
    }

    private fun assertLifecycle(
        code: ElasticsearchIndexLifecycleErrorCode,
        action: () -> Unit,
    ) {
        assertThrownBy<ElasticsearchIndexLifecycleException>(action).satisfies(
            Consumer { error -> error.code.assert().isEqualTo(code) },
        )
    }

    private fun Fixture.execute(
        state: ElasticsearchIndexMigrationState,
        type: ElasticsearchIndexLifecycleCommandType,
    ): ElasticsearchIndexMigrationState = executor.execute(
        command(state, type, "${type.name.lowercase()}-${state.revision}"),
    ).block()!!

    private fun command(
        state: ElasticsearchIndexMigrationState,
        type: ElasticsearchIndexLifecycleCommandType,
        id: String,
    ) = ElasticsearchIndexLifecycleCommand(
        ElasticsearchIndexLifecycleCommandId(id),
        state.manifest.id,
        type,
        state.revision,
    )

    private class ProbeOperations : ElasticsearchIndexLifecycleOperations {
        val calls = mutableListOf<String>()
        val validateCalls = AtomicInteger()
        val failValidationOnce = AtomicBoolean()
        val verificationMatches = AtomicBoolean(true)

        override fun validate(
            command: ElasticsearchIndexLifecycleCommandId,
            manifest: ElasticsearchIndexMigrationManifest,
        ): Mono<ElasticsearchIndexInventory> = Mono.fromCallable {
            calls += "validate"
            validateCalls.incrementAndGet()
            if (failValidationOnce.compareAndSet(true, false)) error("transient validation failure")
            ElasticsearchIndexInventory(
                manifest.names.alias,
                SOURCE,
                mapOf(SOURCE to sourceAttestation()),
                NOW,
            )
        }

        override fun create(
            command: ElasticsearchIndexLifecycleCommandId,
            manifest: ElasticsearchIndexMigrationManifest,
        ): Mono<ElasticsearchIndexAttestation> = Mono.fromCallable {
            calls += "create"
            manifest.destinationAttestation
        }

        override fun rebuild(
            command: ElasticsearchIndexLifecycleCommandId,
            manifest: ElasticsearchIndexMigrationManifest,
        ): Mono<ElasticsearchIndexRebuildReceipt> = Mono.fromCallable {
            calls += "rebuild"
            ElasticsearchIndexRebuildReceipt(
                manifest.names.physical,
                manifest.rebuildStrategy,
                authoritativeWatermark = 42,
                indexedWatermark = 42,
                completedAt = NOW,
            )
        }

        override fun verify(
            command: ElasticsearchIndexLifecycleCommandId,
            manifest: ElasticsearchIndexMigrationManifest,
            physicalIndex: ElasticsearchPhysicalIndex,
        ): Mono<ElasticsearchIndexVerification> = Mono.fromCallable {
            calls += "verify:${physicalIndex.value}"
            val actual = if (verificationMatches.get()) 10L else 9L
            verification(physicalIndex, actual)
        }

        override fun cutover(
            command: ElasticsearchIndexLifecycleCommandId,
            manifest: ElasticsearchIndexMigrationManifest,
        ): Mono<ElasticsearchAliasTransition> = Mono.fromCallable {
            calls += "cutover"
            ElasticsearchAliasTransition(manifest.names.alias, SOURCE, DESTINATION, NOW)
        }

        override fun rollback(
            command: ElasticsearchIndexLifecycleCommandId,
            manifest: ElasticsearchIndexMigrationManifest,
        ): Mono<ElasticsearchAliasTransition> = Mono.fromCallable {
            calls += "rollback"
            ElasticsearchAliasTransition(manifest.names.alias, DESTINATION, SOURCE, NOW)
        }
    }

    private data class Fixture(
        val repository: InMemoryElasticsearchIndexLifecycleRepository,
        val operations: ProbeOperations,
        val executor: ElasticsearchIndexLifecycleExecutor,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-08T02:00:00Z")
        val TARGET = QueryTarget(
            MaterializedNamedAggregate("sales", "order"),
            QueryDocumentKind.SNAPSHOT,
        )
        val SOURCE = ElasticsearchPhysicalIndex("wow.sales.order.snapshot-v0001-000001")
        val DESTINATION = ElasticsearchPhysicalIndex("wow.sales.order.snapshot-v0002-000007")
        val MANIFEST = ElasticsearchIndexMigrationManifest(
            ElasticsearchIndexMigrationId("sales-order-snapshot-v2-g7"),
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

        fun sourceAttestation() = ElasticsearchIndexAttestation(
            SOURCE,
            ElasticsearchIndexMappingVersion(1),
            QueryDocumentKind.SNAPSHOT,
            SchemaContractId("0".repeat(64)),
            ElasticsearchIndexCapabilityDigest("c".repeat(64)),
        )

        fun verification(
            physical: ElasticsearchPhysicalIndex,
            actualCount: Long,
        ) = ElasticsearchIndexVerification(
            physical,
            expectedCount = 10,
            actualCount = actualCount,
            expectedIdentityChecksum = ElasticsearchIndexChecksum("2".repeat(64)),
            actualIdentityChecksum = ElasticsearchIndexChecksum("2".repeat(64)),
            expectedContentChecksum = ElasticsearchIndexChecksum("3".repeat(64)),
            actualContentChecksum = ElasticsearchIndexChecksum("3".repeat(64)),
            versionContinuity = true,
            authoritativeWatermark = 42,
            indexedWatermark = 42,
            recordProbeMismatchCount = 0,
            analyticsProbeMismatchCount = 0,
            verifiedAt = NOW,
        )
    }
}
