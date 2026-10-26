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
import me.ahoo.wow.api.lifecycle.ApplyTags
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.lifecycle.OwnerTransferred
import me.ahoo.wow.api.lifecycle.RestoreAggregate
import me.ahoo.wow.api.lifecycle.SpaceTransferred
import me.ahoo.wow.api.lifecycle.TagsApplied
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
import me.ahoo.wow.spi.snapshot.SnapshotRebuilds

/**
 * The kernel's side of the lifecycle commands (design 01 §6) and of the schedule system commands (decision D198): their
 * decide functions, which read the aggregate's metadata rather than its state, and how their events evolve that
 * metadata. The events leave the user's state as it is unless the model declares an evolve function for them.
 *
 * `wow.schedule.Set` sets the schedule of its key on the aggregate it is sent to, which must exist and must decide the
 * command type it names (review X-7): a name it does not know is rejected as [FrameworkCodes.COMMAND_UNHANDLED] when the
 * schedule is set, not found when it fires, which may be months later for a cron. `wow.schedule.Cancel` cancels it, a
 * no-op when the aggregate keeps no schedule of that key.
 */
internal object KernelLifecycle {
    /** Every lifecycle command, enabled on a model unless it says otherwise. */
    val COMMANDS: Set<Class<*>> = Lifecycle.COMMANDS.values.toSet()

    /** The schedule system commands (decision D198), which every model decides. */
    val SCHEDULE_COMMANDS: Set<Class<*>> = Schedules.COMMANDS.values.toSet()

    /**
     * The kernel's decide function for the lifecycle or schedule command [commandType]. [tagsOf] gives the tags of a
     * state with the applied tags it is given ([AggregateModel.tagsOf], decision D220): what `ApplyTags` would leave.
     * [decides] tells whether the aggregate decides the command type of a name or an alias, which a schedule names.
     */
    fun <S : Any> decider(
        commandType: Class<*>,
        tagsOf: (S, Map<String, List<String>>) -> Map<String, List<String>>,
        decides: (String) -> Boolean,
    ): DecideFunction<S> {
        require(commandType in COMMANDS || commandType in SCHEDULE_COMMANDS) {
            "${commandType.name} is not a lifecycle command."
        }
        return DecideFunction.Sync(Creation.NONE) { state, body ->
            decide((this as MetadataScope).loaded, body, command.ref, decides) { applied -> tagsOf(state, applied) }
        }
    }

    /**
     * The system name of [type], a lifecycle, schedule, job or snapshot rebuild command or event (the jobs' only the
     * built-in job aggregate takes, decision D198; the rebuild's only the built-in rebuild aggregate, decision D172),
     * or null when it is none of them.
     */
    fun systemNameOf(type: Class<*>): String? =
        Lifecycle.nameOf(type) ?: Schedules.nameOf(type) ?: ScheduleJobs.nameOf(type) ?: SnapshotRebuilds.nameOf(type)

    /** Whether [name] is in the system namespace, which only the lifecycle, schedule, job and rebuild types take. */
    fun isSystemName(name: String): Boolean =
        name.startsWith("${Lifecycle.NAMESPACE}.") || name in Schedules.COMMANDS || name in Schedules.EVENTS ||
            name in ScheduleJobs.COMMANDS || name in ScheduleJobs.EVENTS ||
            name in SnapshotRebuilds.COMMANDS || name in SnapshotRebuilds.EVENTS

    /**
     * Decides lifecycle command [body] on the metadata of [loaded]. A command that would change nothing is a no-op;
     * one to a deleted aggregate never gets here unless it restores it. A `SetSchedule` whose command type the
     * aggregate does not decide ([decides]) is rejected.
     *
     * `ApplyTags` is a no-op when the tags it would leave, [tagsOf] its tags (the keys the state derives overriding
     * them, decision D220), are the aggregate's tags already. The loaded tags are the effective ones: comparing them
     * with the applied set alone would commit an event that changes nothing whenever the state derives a key.
     */
    fun decide(
        loaded: CachedState,
        body: Any,
        ref: AggregateRef,
        decides: (String) -> Boolean,
        tagsOf: (Map<String, List<String>>) -> Map<String, List<String>>,
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
        DeleteAggregate -> Decision.events(AggregateDeleted)
        RestoreAggregate -> if (loaded.deleted) Decision.events(AggregateRestored) else Decision.NoOp
        is TransferOwner -> changeOrNoOp(loaded.scope.ownerId == body.ownerId) { OwnerTransferred(body.ownerId) }
        is TransferSpace -> changeOrNoOp(loaded.scope.spaceId == body.spaceId) { SpaceTransferred(body.spaceId) }
        is ApplyTags -> changeOrNoOp(loaded.tags == tagsOf(body.tags)) { TagsApplied(body.tags) }
        else -> throw IllegalArgumentException("${body.javaClass.name} is not a lifecycle command.")
    }

    /**
     * [metadata] after [event]: only the lifecycle events change it. `TagsApplied` sets the applied tags; the kernel
     * then overrides the keys the state derives ([AggregateModel.tagsOf], decision D220).
     */
    fun evolve(metadata: Metadata, event: Any): Metadata = when (event) {
        // Deleting cancels the aggregate's schedules; restoring does not bring them back.
        AggregateDeleted -> metadata.copy(deleted = true, schedules = emptyMap())
        is ScheduleSet -> metadata.copy(schedules = metadata.schedules - event.key + (event.key to event.rule))
        is ScheduleCancelled -> metadata.copy(schedules = metadata.schedules - event.key)
        AggregateRestored -> metadata.copy(deleted = false)
        is OwnerTransferred -> metadata.copy(scope = metadata.scope.copy(ownerId = event.ownerId))
        is SpaceTransferred -> metadata.copy(scope = metadata.scope.copy(spaceId = event.spaceId))
        is TagsApplied -> metadata.copy(tags = event.tags)
        else -> metadata
    }

    private inline fun changeOrNoOp(unchanged: Boolean, event: () -> Any): Decision =
        if (unchanged) Decision.NoOp else Decision.events(event())

    /** The kernel-maintained metadata of an aggregate besides its version. */
    data class Metadata(
        val scope: Scope,
        val deleted: Boolean,
        val tags: Map<String, List<String>>,
        val schedules: Map<String, ScheduleRule> = emptyMap(),
    )

    /** What the kernel's decide functions see beside the command: the committed state they decide on. */
    interface MetadataScope {
        val loaded: CachedState
    }
}
