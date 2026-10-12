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

import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Decision
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

/**
 * The kernel's side of the lifecycle commands (design 01 §6): their decide functions, which read the aggregate's
 * metadata rather than its state, and how their events evolve that metadata. The events leave the user's state as it
 * is unless the model declares an evolve function for them.
 */
internal object KernelLifecycle {
    /** Every lifecycle command, enabled on a model unless it says otherwise. */
    val COMMANDS: Set<Class<*>> = Lifecycle.COMMANDS.values.toSet()

    /** The kernel's decide function for the lifecycle command [commandType]. */
    fun <S : Any> decider(commandType: Class<*>): DecideFunction<S> {
        require(commandType in COMMANDS) { "${commandType.name} is not a lifecycle command." }
        return DecideFunction.Sync(Creation.NONE) { _, body -> decide((this as MetadataScope).loaded, body) }
    }

    /**
     * Decides lifecycle command [body] on the metadata of [loaded]. A command that would change nothing is a no-op;
     * one to a deleted aggregate never gets here unless it restores it.
     */
    fun decide(loaded: CachedState, body: Any): Decision = when (body) {
        DeleteAggregate -> Decision.events(AggregateDeleted)
        RestoreAggregate -> if (loaded.deleted) Decision.events(AggregateRestored) else Decision.NoOp
        is TransferOwner -> changeOrNoOp(loaded.scope.ownerId == body.ownerId) { OwnerTransferred(body.ownerId) }
        is TransferSpace -> changeOrNoOp(loaded.scope.spaceId == body.spaceId) { SpaceTransferred(body.spaceId) }
        is ApplyTags -> changeOrNoOp(loaded.tags == body.tags) { TagsApplied(body.tags) }
        else -> throw IllegalArgumentException("${body.javaClass.name} is not a lifecycle command.")
    }

    /** [metadata] after [event]: only the lifecycle events change it. */
    fun evolve(metadata: Metadata, event: Any): Metadata = when (event) {
        AggregateDeleted -> metadata.copy(deleted = true)
        AggregateRestored -> metadata.copy(deleted = false)
        is OwnerTransferred -> metadata.copy(scope = metadata.scope.copy(ownerId = event.ownerId))
        is SpaceTransferred -> metadata.copy(scope = metadata.scope.copy(spaceId = event.spaceId))
        is TagsApplied -> metadata.copy(tags = event.tags)
        else -> metadata
    }

    private inline fun changeOrNoOp(unchanged: Boolean, event: () -> Any): Decision =
        if (unchanged) Decision.NoOp else Decision.events(event())

    /** The kernel-maintained metadata of an aggregate besides its version. */
    data class Metadata(val scope: Scope, val deleted: Boolean, val tags: Map<String, List<String>>)

    /** What the kernel's decide functions see beside the command: the committed state they decide on. */
    interface MetadataScope {
        val loaded: CachedState
    }
}
