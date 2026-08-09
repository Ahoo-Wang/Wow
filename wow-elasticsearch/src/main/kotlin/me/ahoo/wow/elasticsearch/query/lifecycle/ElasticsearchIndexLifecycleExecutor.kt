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

package me.ahoo.wow.elasticsearch.query.lifecycle

import reactor.core.publisher.Mono
import java.time.Clock
import java.time.DateTimeException

internal interface ElasticsearchIndexLifecycleRepository {
    fun create(state: ElasticsearchIndexMigrationState): Mono<ElasticsearchIndexLifecycleStoredState>

    fun load(id: ElasticsearchIndexMigrationId): Mono<ElasticsearchIndexLifecycleStoredState>

    fun compareAndSet(
        expected: ElasticsearchIndexLifecycleStoredState,
        state: ElasticsearchIndexMigrationState,
    ): Mono<ElasticsearchIndexLifecycleStoredState>
}

internal sealed interface ElasticsearchIndexLifecycleRepositoryVersion {
    data class InMemory(val value: Long) : ElasticsearchIndexLifecycleRepositoryVersion

    data class Elasticsearch(
        val sequenceNumber: Long,
        val primaryTerm: Long,
    ) : ElasticsearchIndexLifecycleRepositoryVersion
}

internal data class ElasticsearchIndexLifecycleStoredState(
    val state: ElasticsearchIndexMigrationState,
    val version: ElasticsearchIndexLifecycleRepositoryVersion,
)

internal interface ElasticsearchIndexLifecycleOperations {
    fun validate(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
    ): Mono<ElasticsearchIndexInventory>

    fun create(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
    ): Mono<ElasticsearchIndexAttestation>

    fun rebuild(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
    ): Mono<ElasticsearchIndexRebuildReceipt>

    fun verify(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
        physicalIndex: ElasticsearchPhysicalIndex,
    ): Mono<ElasticsearchIndexVerification>

    fun cutover(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
    ): Mono<ElasticsearchAliasTransition>

    fun rollback(
        command: ElasticsearchIndexLifecycleCommandId,
        manifest: ElasticsearchIndexMigrationManifest,
    ): Mono<ElasticsearchAliasTransition>
}

/** Trusted pause/drain or mirror boundary required before a Snapshot alias can move. */
internal fun interface ElasticsearchSnapshotCutoverGuard {
    fun awaitFence(
        manifest: ElasticsearchIndexMigrationManifest,
        verification: ElasticsearchIndexVerification,
    ): Mono<Void>

    companion object {
        val DENY: ElasticsearchSnapshotCutoverGuard = ElasticsearchSnapshotCutoverGuard { manifest, _ ->
            lifecycleError(
                ElasticsearchIndexLifecycleErrorCode.CUTOVER_FENCE_REQUIRED,
                manifest.id,
                "Snapshot cutover requires an explicit pause/drain or controlled-mirror write fence.",
            )
        }
    }
}

internal class ElasticsearchIndexLifecycleExecutor(
    private val repository: ElasticsearchIndexLifecycleRepository,
    private val operations: ElasticsearchIndexLifecycleOperations,
    private val clock: Clock,
    private val snapshotCutoverGuard: ElasticsearchSnapshotCutoverGuard = ElasticsearchSnapshotCutoverGuard.DENY,
) {
    fun register(manifest: ElasticsearchIndexMigrationManifest): Mono<ElasticsearchIndexMigrationState> =
        Mono.defer {
            val initial = ElasticsearchIndexMigrationState.initial(manifest)
            repository.create(initial).map(ElasticsearchIndexLifecycleStoredState::state)
                .switchIfEmpty(
                    load(manifest.id).flatMap { existing ->
                        if (existing.state.manifest == manifest) {
                            Mono.just(existing.state)
                        } else {
                            lifecycleError(
                                ElasticsearchIndexLifecycleErrorCode.MIGRATION_CONFLICT,
                                manifest.id,
                                "Elasticsearch migration id is already registered with another manifest.",
                            )
                        }
                    }
                )
        }

    fun plan(command: ElasticsearchIndexLifecycleCommand): Mono<ElasticsearchIndexLifecycleCommandPlan> =
        Mono.defer { load(command.migrationId).map { stored -> stored.state.plan(command) } }

    fun execute(command: ElasticsearchIndexLifecycleCommand): Mono<ElasticsearchIndexMigrationState> =
        Mono.defer { claim(command).flatMap { state -> executeClaimed(command, state) } }

    private fun claim(command: ElasticsearchIndexLifecycleCommand): Mono<ElasticsearchIndexMigrationState> =
        load(command.migrationId).flatMap { stored ->
            val current = stored.state
            current.lastCompletedCommand?.takeIf { it.id == command.id }?.let { completed ->
                if (completed.type != command.type) {
                    return@flatMap lifecycleError(
                        ElasticsearchIndexLifecycleErrorCode.COMMAND_CONFLICT,
                        current.manifest.id,
                        "Completed command id is reused for another command type.",
                    )
                }
                return@flatMap Mono.just(current)
            }
            val claimed = current.claim(command, clock.instant())
            if (claimed === current) {
                Mono.just(current)
            } else {
                repository.compareAndSet(stored, claimed)
                    .map(ElasticsearchIndexLifecycleStoredState::state)
                    .switchIfEmpty(Mono.defer { claim(command) })
            }
        }

    private fun executeClaimed(
        command: ElasticsearchIndexLifecycleCommand,
        state: ElasticsearchIndexMigrationState,
    ): Mono<ElasticsearchIndexMigrationState> {
        state.lastCompletedCommand?.takeIf { it.id == command.id }?.let { return Mono.just(state) }
        val result = when (command.type) {
            ElasticsearchIndexLifecycleCommandType.VALIDATE -> executeValidate(command, state)
            ElasticsearchIndexLifecycleCommandType.CREATE -> executeCreate(command, state)
            ElasticsearchIndexLifecycleCommandType.REBUILD -> executeRebuild(command, state)
            ElasticsearchIndexLifecycleCommandType.VERIFY -> executeVerify(command, state)
            ElasticsearchIndexLifecycleCommandType.CUTOVER -> executeCutover(command, state)
            ElasticsearchIndexLifecycleCommandType.ROLLBACK -> executeRollback(command, state)
        }
        return result.onErrorMap { error ->
            if (error is ElasticsearchIndexLifecycleException) {
                error
            } else {
                ElasticsearchIndexLifecycleException(
                    ElasticsearchIndexLifecycleErrorCode.OPERATION_FAILED,
                    state.manifest.id,
                    "Elasticsearch lifecycle command ${command.type} failed.",
                    error,
                )
            }
        }
    }

    private fun executeValidate(
        command: ElasticsearchIndexLifecycleCommand,
        state: ElasticsearchIndexMigrationState,
    ): Mono<ElasticsearchIndexMigrationState> = operations.validate(command.id, state.manifest)
        .requireValue(command, state)
        .flatMap { inventory ->
            state.manifest.validate(inventory)
            complete(command, state) { copy(inventory = inventory) }
        }

    private fun executeCreate(
        command: ElasticsearchIndexLifecycleCommand,
        state: ElasticsearchIndexMigrationState,
    ): Mono<ElasticsearchIndexMigrationState> = operations.create(command.id, state.manifest)
        .requireValue(command, state)
        .flatMap { attestation ->
            if (attestation != state.manifest.destinationAttestation) {
                return@flatMap lifecycleError(
                    ElasticsearchIndexLifecycleErrorCode.ATTESTATION_MISMATCH,
                    state.manifest.id,
                    "Created Elasticsearch index attestation does not match the manifest.",
                )
            }
            complete(command, state) { copy(destinationAttestation = attestation) }
        }

    private fun executeRebuild(
        command: ElasticsearchIndexLifecycleCommand,
        state: ElasticsearchIndexMigrationState,
    ): Mono<ElasticsearchIndexMigrationState> = operations.rebuild(command.id, state.manifest)
        .requireValue(command, state)
        .flatMap { receipt ->
            receipt.requireMatches(state.manifest)
            complete(command, state) { copy(rebuildReceipt = receipt) }
        }

    private fun executeVerify(
        command: ElasticsearchIndexLifecycleCommand,
        state: ElasticsearchIndexMigrationState,
    ): Mono<ElasticsearchIndexMigrationState> {
        val physical = when (state.phase) {
            ElasticsearchIndexLifecyclePhase.REBUILT -> state.manifest.names.physical
            ElasticsearchIndexLifecyclePhase.CUTOVER -> state.manifest.sourcePhysicalIndex
            else -> return lifecycleError(
                ElasticsearchIndexLifecycleErrorCode.INVALID_TRANSITION,
                state.manifest.id,
                "Elasticsearch verification is not allowed from ${state.phase}.",
            )
        }
        return operations.verify(command.id, state.manifest, physical)
            .requireValue(command, state)
            .flatMap { verification ->
                verification.requireSatisfied(state.manifest, physical)
                complete(command, state) {
                    when (phase) {
                        ElasticsearchIndexLifecyclePhase.REBUILT -> copy(destinationVerification = verification)
                        ElasticsearchIndexLifecyclePhase.CUTOVER -> copy(rollbackVerification = verification)
                        else -> error("Verification phase changed while command was active.")
                    }
                }
            }
    }

    private fun executeCutover(
        command: ElasticsearchIndexLifecycleCommand,
        state: ElasticsearchIndexMigrationState,
    ): Mono<ElasticsearchIndexMigrationState> = requireCutoverFence(state)
        .then(operations.cutover(command.id, state.manifest))
        .requireValue(command, state)
        .flatMap { transition ->
            requireTransition(
                state,
                transition,
                state.manifest.sourcePhysicalIndex,
                state.manifest.names.physical,
            )
            val retainUntil = try {
                transition.transitionedAt.plus(state.manifest.minimumRetention)
            } catch (error: DateTimeException) {
                return@flatMap Mono.error(error)
            } catch (error: ArithmeticException) {
                return@flatMap Mono.error(error)
            }
            complete(command, state) { copy(cutover = transition, retainedSourceUntil = retainUntil) }
        }

    private fun requireCutoverFence(state: ElasticsearchIndexMigrationState): Mono<Void> =
        if (state.manifest.target.documentKind == me.ahoo.wow.query.gateway.QueryDocumentKind.SNAPSHOT) {
            snapshotCutoverGuard.awaitFence(
                state.manifest,
                requireNotNull(state.destinationVerification) {
                    "Verified lifecycle state must retain destination verification."
                },
            )
        } else {
            Mono.empty()
        }

    private fun executeRollback(
        command: ElasticsearchIndexLifecycleCommand,
        state: ElasticsearchIndexMigrationState,
    ): Mono<ElasticsearchIndexMigrationState> = operations.rollback(command.id, state.manifest)
        .requireValue(command, state)
        .flatMap { transition ->
            requireTransition(
                state,
                transition,
                state.manifest.names.physical,
                state.manifest.sourcePhysicalIndex,
            )
            complete(command, state) { copy(rollback = transition) }
        }

    private fun requireTransition(
        state: ElasticsearchIndexMigrationState,
        transition: ElasticsearchAliasTransition,
        previous: ElasticsearchPhysicalIndex?,
        current: ElasticsearchPhysicalIndex,
    ) {
        if (
            transition.alias != state.manifest.names.alias ||
            transition.previous != previous ||
            transition.current != current
        ) {
            reject(
                ElasticsearchIndexLifecycleErrorCode.ALIAS_CONFLICT,
                state.manifest.id,
                "Elasticsearch alias transition does not match the expected compare-and-set.",
            )
        }
    }

    private fun complete(
        command: ElasticsearchIndexLifecycleCommand,
        claimed: ElasticsearchIndexMigrationState,
        update: ElasticsearchIndexMigrationState.() -> ElasticsearchIndexMigrationState,
    ): Mono<ElasticsearchIndexMigrationState> {
        val completed = claimed.complete(command, clock.instant(), update)
        return load(claimed.manifest.id).flatMap { currentStored ->
            if (currentStored.state != claimed) {
                return@flatMap completedOrConflict(command, claimed, currentStored.state)
            }
            repository.compareAndSet(currentStored, completed)
                .map(ElasticsearchIndexLifecycleStoredState::state)
                .switchIfEmpty(
                    load(claimed.manifest.id).flatMap { latest ->
                        completedOrConflict(command, claimed, latest.state)
                    },
                )
        }
    }

    private fun completedOrConflict(
        command: ElasticsearchIndexLifecycleCommand,
        claimed: ElasticsearchIndexMigrationState,
        current: ElasticsearchIndexMigrationState,
    ): Mono<ElasticsearchIndexMigrationState> =
        if (current.lastCompletedCommand?.id == command.id) {
            Mono.just(current)
        } else {
            lifecycleError(
                ElasticsearchIndexLifecycleErrorCode.STATE_CONFLICT,
                claimed.manifest.id,
                "Elasticsearch migration changed while completing the command.",
            )
        }

    private fun <T : Any> Mono<T>.requireValue(
        command: ElasticsearchIndexLifecycleCommand,
        state: ElasticsearchIndexMigrationState,
    ): Mono<T> = switchIfEmpty(
        lifecycleError(
            ElasticsearchIndexLifecycleErrorCode.OPERATION_FAILED,
            state.manifest.id,
            "Elasticsearch lifecycle command ${command.type} returned no result.",
        ),
    )

    private fun load(id: ElasticsearchIndexMigrationId): Mono<ElasticsearchIndexLifecycleStoredState> =
        repository.load(id).switchIfEmpty(
            lifecycleError(
                ElasticsearchIndexLifecycleErrorCode.MIGRATION_NOT_FOUND,
                id,
                "Elasticsearch index migration [${id.value}] is not registered.",
            ),
        )
}

internal class InMemoryElasticsearchIndexLifecycleRepository : ElasticsearchIndexLifecycleRepository {
    private val states = java.util.concurrent.ConcurrentHashMap<
        ElasticsearchIndexMigrationId,
        ElasticsearchIndexMigrationState,
        >()

    override fun create(state: ElasticsearchIndexMigrationState): Mono<ElasticsearchIndexLifecycleStoredState> =
        Mono.defer {
            if (states.putIfAbsent(state.manifest.id, state) == null) {
                Mono.just(state.stored())
            } else {
                Mono.empty()
            }
        }

    override fun load(id: ElasticsearchIndexMigrationId): Mono<ElasticsearchIndexLifecycleStoredState> =
        Mono.defer { Mono.justOrEmpty(states[id]?.stored()) }

    override fun compareAndSet(
        expected: ElasticsearchIndexLifecycleStoredState,
        state: ElasticsearchIndexMigrationState,
    ): Mono<ElasticsearchIndexLifecycleStoredState> = Mono.defer {
        val id = expected.state.manifest.id
        require(id == state.manifest.id) { "Elasticsearch migration state id must match repository key." }
        val expectedVersion = expected.version as? ElasticsearchIndexLifecycleRepositoryVersion.InMemory
            ?: error("In-memory repository requires an in-memory storage version.")
        val updated = states.computeIfPresent(id) { _, current ->
            if (current.revision == expectedVersion.value && current == expected.state) state else current
        }
        if (updated === state) Mono.just(state.stored()) else Mono.empty()
    }

    fun get(id: ElasticsearchIndexMigrationId): ElasticsearchIndexMigrationState? = states[id]

    private fun ElasticsearchIndexMigrationState.stored() = ElasticsearchIndexLifecycleStoredState(
        this,
        ElasticsearchIndexLifecycleRepositoryVersion.InMemory(revision),
    )
}

private fun <T : Any> lifecycleError(
    code: ElasticsearchIndexLifecycleErrorCode,
    id: ElasticsearchIndexMigrationId,
    message: String,
): Mono<T> = Mono.error(ElasticsearchIndexLifecycleException(code, id, message))
