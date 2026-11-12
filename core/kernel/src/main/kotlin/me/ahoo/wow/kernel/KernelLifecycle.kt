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

package me.ahoo.wow.kernel

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.api.lifecycle.AggregateDeleted
import me.ahoo.wow.api.lifecycle.AggregateRestored
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.lifecycle.OwnerTransferred
import me.ahoo.wow.api.lifecycle.RestoreAggregate
import me.ahoo.wow.api.lifecycle.SpaceTransferred
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.api.lifecycle.TransferSpace
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.schedule.CancelSchedule
import me.ahoo.wow.api.schedule.ScheduleCancelled
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.ScheduleSet
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.api.schedule.SetSchedule
import me.ahoo.wow.spi.SystemCommandGroups
import me.ahoo.wow.spi.snapshot.SnapshotRebuilds

/**
 * The kernel's side of the lifecycle commands and of the schedule system commands: their
 * decide functions, which read the aggregate's metadata rather than its state, and how their events evolve that
 * metadata. The events leave the user's state as it is unless the model declares an evolve function for them.
 *
 * `wow.schedule.SetSchedule` sets the schedule of its key on the aggregate it is sent to, which must exist and must decide the
 * command type it names: a name it does not know is rejected as [FrameworkCodes.COMMAND_UNHANDLED] when the
 * schedule is set, not found when it fires, which may be months later for a cron. `wow.schedule.CancelSchedule` cancels it, a
 * no-op when the aggregate keeps no schedule of that key.
 */
internal object KernelLifecycle {
    /** Every lifecycle command, enabled on a model unless it says otherwise. */
    val COMMANDS: Set<Class<*>> = SystemCommandGroups.Group.LIFECYCLE.commands.values.toSet()

    /** The schedule system commands, which every model decides. */
    val SCHEDULE_COMMANDS: Set<Class<*>> = SystemCommandGroups.Group.SCHEDULE.commands.values.toSet()

    /**
     * The stored names of the lifecycle events Wow no longer has, which every model retires ([AggregateModel.isRetired]):
     * a log a released version wrote still holds them, so an aggregate loads, replays and is read past them, its
     * version counting their streams. `TagsApplied` went with the tags, which the state's access attributes replace.
     */
    val RETIRED_EVENTS: Set<String> = setOf("${Lifecycle.NAMESPACE}.TagsApplied")

    /**
     * The kernel's decide function for the lifecycle or schedule command [commandType].
     * [decides] tells whether the aggregate decides the command type of a name or an alias, which a schedule names.
     */
    fun <S : Any> decider(
        commandType: Class<*>,
        decides: (String) -> Boolean,
    ): DecideFunction<S> {
        require(commandType in SystemCommandGroups.DECIDED_BY_EVERY_AGGREGATE) {
            "${commandType.name} is not a lifecycle command."
        }
        return DecideFunction.Sync(Creation.NONE) { _, body ->
            decide((this as MetadataScope).loaded, body, command.ref, decides)
        }
    }

    /**
     * The system name of [type], a command a model of the framework decides
     * ([SystemCommandGroups.DECIDED_BY_A_SYSTEM_MODEL]: the jobs' only the built-in job aggregate takes, the rebuild's
     * only the built-in rebuild aggregate) or one of their groups' events, or null when it is none of them.
     */
    fun systemNameOf(type: Class<*>): String? =
        SystemCommandGroups.DECIDED_BY_A_SYSTEM_MODEL.nameOf(type) ?: SYSTEM_EVENT_NAMES[type]

    /**
     * Whether [name] is in the system namespace, which only the commands a model of the framework decides
     * ([SystemCommandGroups.DECIDED_BY_A_SYSTEM_MODEL]) and their groups' events take, the retired lifecycle ones too.
     */
    fun isSystemName(name: String): Boolean =
        name.startsWith("${Lifecycle.NAMESPACE}.") || name in SystemCommandGroups.DECIDED_BY_A_SYSTEM_MODEL ||
            name in SYSTEM_EVENTS

    /** The events of the groups of [SystemCommandGroups.DECIDED_BY_A_SYSTEM_MODEL], by their system names. */
    private val SYSTEM_EVENTS: Map<String, Class<*>> =
        Lifecycle.EVENTS + Schedules.EVENTS + ScheduleJobs.EVENTS + SnapshotRebuilds.EVENTS

    private val SYSTEM_EVENT_NAMES: Map<Class<*>, String> = SYSTEM_EVENTS.entries.associate { (name, type) -> type to name }

    /**
     * Decides lifecycle command [body] on the metadata of [loaded]. A command that would change nothing is a no-op;
     * one to a deleted aggregate never gets here unless it restores it. A `SetSchedule` whose command type the
     * aggregate does not decide ([decides]) is rejected.
     */
    fun decide(
        loaded: CachedState,
        body: Any,
        ref: AggregateRef,
        decides: (String) -> Boolean,
    ): Decision = when (body) {
        is SetSchedule -> if (decides(body.commandType)) {
            Decision.events(ScheduleSet(body.key, body.rule, ref, body.commandType, body.command))
        } else {
            Decision.Rejected(
                Rejection(
                    FrameworkCodes.COMMAND_UNHANDLED,
                    "${ref.type} has no command type ${body.commandType}, which the schedule ${body.key} would send.",
                ),
            )
        }
        is CancelSchedule -> changeOrNoOp(body.key !in loaded.schedules) { ScheduleCancelled(body.key) }
        is DeleteAggregate -> Decision.events(AggregateDeleted())
        is RestoreAggregate -> if (loaded.deleted) Decision.events(AggregateRestored()) else Decision.NoOp
        is TransferOwner -> changeOrNoOp(loaded.scope.ownerId == body.ownerId) { OwnerTransferred(body.ownerId) }
        is TransferSpace -> changeOrNoOp(loaded.scope.spaceId == body.spaceId) { SpaceTransferred(body.spaceId) }
        else -> throw IllegalArgumentException("${body.javaClass.name} is not a lifecycle command.")
    }

    /** [metadata] after [event]: only the lifecycle and schedule events change it. */
    fun evolve(metadata: Metadata, event: Any): Metadata = when (event) {
        // Deleting cancels the aggregate's schedules; restoring does not bring them back.
        is AggregateDeleted -> metadata.copy(deleted = true, schedules = emptyMap())
        is ScheduleSet -> metadata.copy(schedules = metadata.schedules - event.key + (event.key to event.rule))
        is ScheduleCancelled -> metadata.copy(schedules = metadata.schedules - event.key)
        is AggregateRestored -> metadata.copy(deleted = false)
        is OwnerTransferred -> metadata.copy(scope = metadata.scope.copy(ownerId = event.ownerId))
        is SpaceTransferred -> metadata.copy(scope = metadata.scope.copy(spaceId = event.spaceId))
        else -> metadata
    }

    private inline fun changeOrNoOp(unchanged: Boolean, event: () -> Any): Decision =
        if (unchanged) Decision.NoOp else Decision.events(event())

    /** The kernel-maintained metadata of an aggregate besides its version. */
    data class Metadata(
        val scope: Scope,
        val deleted: Boolean,
        val schedules: Map<String, ScheduleRule> = emptyMap(),
    )

    /** What the kernel's decide functions see beside the command: the committed state they decide on. */
    interface MetadataScope {
        val loaded: CachedState
    }
}
