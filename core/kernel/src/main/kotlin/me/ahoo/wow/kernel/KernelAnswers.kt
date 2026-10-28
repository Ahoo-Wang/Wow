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

import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.lifecycle.RestoreAggregate
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.spi.log.EventStreamRecord

// The kernel's answers that need nothing but the command and what the log holds: its refusals before deciding and
// its answers to a request already committed.

/**
 * Refuses [command] before deciding when the aggregate as [loaded] cannot take it, checked in this order: it does
 * not exist and the command does not create it; it exists in another scope than the command's; it is
 * deleted and the command neither restores it nor is decided on a deleted aggregate ([DecideFunction.onDeleted]); it
 * exists and the command must create it; it is not at the expected version.
 *
 * The scope comes right after existence, so a caller outside the aggregate's scope learns only that it exists,
 * which a colliding creation would tell it anyway: not whether it is deleted, nor its version.
 */
internal fun admissible(decider: DecideFunction<*>, command: Command<*>, loaded: CachedState): CommandOutcome? {
    val version = loaded.version
    return when {
        version == 0 && decider.creation == Creation.NONE ->
            rejected(FrameworkCodes.AGGREGATE_NOT_FOUND, "${command.ref} does not exist.")

        version > 0 && !command.isDerived && !admits(loaded.scope, command.scope) -> forbidden(command)

        loaded.deleted && !decider.onDeleted && command.body != RestoreAggregate ->
            rejected(FrameworkCodes.AGGREGATE_DELETED, "${command.ref} is deleted.")

        version > 0 && decider.creation == Creation.REQUIRED ->
            rejected(FrameworkCodes.AGGREGATE_EXISTS, "${command.ref} already exists.")

        command.expectedVersion != null && command.expectedVersion != version ->
            rejected(
                FrameworkCodes.AGGREGATE_VERSION_MISMATCH,
                "${command.ref} is at version $version, not ${command.expectedVersion}.",
            )

        else -> null
    }
}

/**
 * Answers [command], whose request id [existing] already committed: a retry gets that stream back, before the
 * aggregate is loaded and without deciding again.
 *
 * A retry comes from the scope that sent the command, so the answer is given only within that scope: a
 * caller outside it, reusing or guessing another caller's request id, learns neither the stream nor which command
 * committed it, so this check comes before the command types are compared. That scope is the aggregate's before
 * the stream, but the stream records only its scope after; the two differ only in a part the stream's own events
 * transferred, and that part is not checked: retrying a transfer is still answered though the caller no longer
 * holds the aggregate. A derived command is answered whatever its scope: its retry is the server
 * handling the same commit again, not a caller.
 */
internal fun duplicate(existing: EventStreamRecord, command: Command<*>, commandType: CommandType): CommandOutcome {
    val names = existing.events.mapTo(HashSet()) { it.name }
    val before = Scope(
        if (Lifecycle.OWNER_TRANSFERRED in names) command.scope.ownerId else existing.scope.ownerId,
        if (Lifecycle.SPACE_TRANSFERRED in names) command.scope.spaceId else existing.scope.spaceId,
    )
    return if (command.isDerived || admits(before, command.scope)) {
        settled(existing, commandType)
    } else {
        forbidden(command)
    }
}

/** Whether an aggregate in scope [aggregate] takes a command in scope [caller]: each part the caller has matches. */
internal fun admits(aggregate: Scope, caller: Scope): Boolean =
    (caller.ownerId == null || caller.ownerId == aggregate.ownerId) &&
        (caller.spaceId == null || caller.spaceId == aggregate.spaceId)

internal fun forbidden(command: Command<*>) =
    rejected(FrameworkCodes.AGGREGATE_FORBIDDEN, "${command.ref} is outside the command's scope.")

/** A stream recorded under the command's name, or a former one (`@Alias`), is the same command's. */
internal fun settled(existing: EventStreamRecord, commandType: CommandType): CommandOutcome =
    if (commandType.isNamed(existing.commandType)) {
        // A retry of a command rejected with events is answered with the same rejection.
        existing.rejection?.let { CommandOutcome.Rejected(it, existing.version, duplicate = true) }
            ?: CommandOutcome.AlreadyCommitted(existing)
    } else {
        rejected(
            FrameworkCodes.COMMAND_REQUEST_ID_REUSED,
            "Request ${existing.requestId} was already used by another command.",
        )
    }

internal fun rejected(code: String, message: String) = CommandOutcome.Rejected(Rejection(code, message))
