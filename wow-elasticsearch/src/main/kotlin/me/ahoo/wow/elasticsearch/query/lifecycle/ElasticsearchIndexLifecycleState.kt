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

import java.time.Instant

internal enum class ElasticsearchIndexLifecycleCommandType {
    VALIDATE,
    CREATE,
    REBUILD,
    VERIFY,
    CUTOVER,
    ROLLBACK,
}

internal enum class ElasticsearchIndexLifecyclePhase {
    NEW,
    VALIDATED,
    CREATED,
    REBUILT,
    VERIFIED,
    CUTOVER,
    ROLLBACK_VERIFIED,
    ROLLED_BACK,
}

internal data class ElasticsearchIndexLifecycleCommand(
    val id: ElasticsearchIndexLifecycleCommandId,
    val migrationId: ElasticsearchIndexMigrationId,
    val type: ElasticsearchIndexLifecycleCommandType,
    val expectedRevision: Long,
) {
    init {
        require(expectedRevision >= 0) { "Expected Elasticsearch migration revision must not be negative." }
    }
}

internal data class ElasticsearchIndexActiveCommand(
    val id: ElasticsearchIndexLifecycleCommandId,
    val type: ElasticsearchIndexLifecycleCommandType,
    val from: ElasticsearchIndexLifecyclePhase,
    val to: ElasticsearchIndexLifecyclePhase,
    val startedAt: Instant,
)

internal data class ElasticsearchIndexCompletedCommand(
    val id: ElasticsearchIndexLifecycleCommandId,
    val type: ElasticsearchIndexLifecycleCommandType,
    val completedAt: Instant,
)

@Suppress("LongParameterList")
internal data class ElasticsearchIndexMigrationState(
    val manifest: ElasticsearchIndexMigrationManifest,
    val phase: ElasticsearchIndexLifecyclePhase,
    val revision: Long,
    val activeCommand: ElasticsearchIndexActiveCommand? = null,
    val lastCompletedCommand: ElasticsearchIndexCompletedCommand? = null,
    val inventory: ElasticsearchIndexInventory? = null,
    val destinationAttestation: ElasticsearchIndexAttestation? = null,
    val rebuildReceipt: ElasticsearchIndexRebuildReceipt? = null,
    val destinationVerification: ElasticsearchIndexVerification? = null,
    val cutover: ElasticsearchAliasTransition? = null,
    val rollbackVerification: ElasticsearchIndexVerification? = null,
    val rollback: ElasticsearchAliasTransition? = null,
    val retainedSourceUntil: Instant? = null,
) {
    init {
        require(revision >= 0) { "Elasticsearch migration revision must not be negative." }
        activeCommand?.let { command -> require(command.from == phase) }
    }

    fun plan(command: ElasticsearchIndexLifecycleCommand): ElasticsearchIndexLifecycleCommandPlan {
        requireMigration(command)
        lastCompletedCommand?.takeIf { completed -> completed.id == command.id }?.let { completed ->
            require(completed.type == command.type)
            return ElasticsearchIndexLifecycleCommandPlan(command, phase, phase, resumed = true)
        }
        activeCommand?.let { active ->
            if (active.id != command.id || active.type != command.type) {
                reject(
                    ElasticsearchIndexLifecycleErrorCode.COMMAND_CONFLICT,
                    manifest.id,
                    "Elasticsearch migration already has an active command.",
                )
            }
            return ElasticsearchIndexLifecycleCommandPlan(command, active.from, active.to, resumed = true)
        }
        if (command.expectedRevision != revision) {
            reject(
                ElasticsearchIndexLifecycleErrorCode.STATE_CONFLICT,
                manifest.id,
                "Elasticsearch migration revision changed from ${command.expectedRevision} to $revision.",
            )
        }
        return ElasticsearchIndexLifecycleCommandPlan(command, phase, nextPhase(command.type), resumed = false)
    }

    fun claim(
        command: ElasticsearchIndexLifecycleCommand,
        now: Instant,
    ): ElasticsearchIndexMigrationState {
        val plan = plan(command)
        if (plan.resumed) return this
        return copy(
            revision = revision + 1,
            activeCommand = ElasticsearchIndexActiveCommand(command.id, command.type, plan.from, plan.to, now),
        )
    }

    fun complete(
        command: ElasticsearchIndexLifecycleCommand,
        now: Instant,
        update: ElasticsearchIndexMigrationState.() -> ElasticsearchIndexMigrationState,
    ): ElasticsearchIndexMigrationState {
        val active = requireNotNull(activeCommand) { "Elasticsearch migration has no active command." }
        if (active.id != command.id || active.type != command.type) {
            reject(
                ElasticsearchIndexLifecycleErrorCode.COMMAND_CONFLICT,
                manifest.id,
                "Elasticsearch migration active command does not match completion.",
            )
        }
        val updated = update()
        return updated.copy(
            phase = active.to,
            revision = revision + 1,
            activeCommand = null,
            lastCompletedCommand = ElasticsearchIndexCompletedCommand(command.id, command.type, now),
        )
    }

    private fun requireMigration(command: ElasticsearchIndexLifecycleCommand) {
        if (command.migrationId != manifest.id) {
            reject(
                ElasticsearchIndexLifecycleErrorCode.MIGRATION_CONFLICT,
                manifest.id,
                "Elasticsearch lifecycle command targets another migration.",
            )
        }
    }

    private fun nextPhase(type: ElasticsearchIndexLifecycleCommandType): ElasticsearchIndexLifecyclePhase =
        when (phase to type) {
            ElasticsearchIndexLifecyclePhase.NEW to ElasticsearchIndexLifecycleCommandType.VALIDATE ->
                ElasticsearchIndexLifecyclePhase.VALIDATED

            ElasticsearchIndexLifecyclePhase.VALIDATED to ElasticsearchIndexLifecycleCommandType.CREATE ->
                ElasticsearchIndexLifecyclePhase.CREATED

            ElasticsearchIndexLifecyclePhase.CREATED to ElasticsearchIndexLifecycleCommandType.REBUILD ->
                ElasticsearchIndexLifecyclePhase.REBUILT

            ElasticsearchIndexLifecyclePhase.REBUILT to ElasticsearchIndexLifecycleCommandType.VERIFY ->
                ElasticsearchIndexLifecyclePhase.VERIFIED

            ElasticsearchIndexLifecyclePhase.VERIFIED to ElasticsearchIndexLifecycleCommandType.CUTOVER ->
                ElasticsearchIndexLifecyclePhase.CUTOVER

            ElasticsearchIndexLifecyclePhase.CUTOVER to ElasticsearchIndexLifecycleCommandType.VERIFY -> {
                ElasticsearchIndexLifecyclePhase.ROLLBACK_VERIFIED
            }

            ElasticsearchIndexLifecyclePhase.ROLLBACK_VERIFIED to ElasticsearchIndexLifecycleCommandType.ROLLBACK ->
                ElasticsearchIndexLifecyclePhase.ROLLED_BACK

            else -> invalidTransition(type)
        }

    private fun invalidTransition(type: ElasticsearchIndexLifecycleCommandType): Nothing = reject(
        ElasticsearchIndexLifecycleErrorCode.INVALID_TRANSITION,
        manifest.id,
        "Elasticsearch lifecycle command $type is not allowed from $phase.",
    )

    companion object {
        fun initial(manifest: ElasticsearchIndexMigrationManifest): ElasticsearchIndexMigrationState =
            ElasticsearchIndexMigrationState(manifest, ElasticsearchIndexLifecyclePhase.NEW, revision = 0)
    }
}

internal data class ElasticsearchIndexLifecycleCommandPlan(
    val command: ElasticsearchIndexLifecycleCommand,
    val from: ElasticsearchIndexLifecyclePhase,
    val to: ElasticsearchIndexLifecyclePhase,
    val resumed: Boolean,
)
