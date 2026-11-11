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

// The kernel's answers that need nothing but the command and what the log holds: its refusals before and after
// deciding and its answers to a request already committed.

/**
 * Refuses [command] before deciding when the aggregate as [loaded] cannot take it, checked in this order: it does not
 * exist and the command does not create it; the command's caller may not change it ([reach]); it is deleted and the
 * command neither restores it nor is decided on a deleted aggregate ([DecideFunction.onDeleted]); it exists and the
 * command must create it; it is not at the expected version.
 *
 * An aggregate the caller may not change is answered as one that does not exist, with the same code and message, as
 * a read of it is, unless the caller may read it: the caller learns nothing of it, neither that it exists, nor whether
 * it is deleted, nor its version. One the caller may read but not change is [FrameworkCodes.AGGREGATE_FORBIDDEN], and
 * so is one a command that may create the aggregate names: whether such a command succeeds already tells whether the
 * aggregate exists, so not found would be false and hide nothing. Whether the caller may read it is evaluated only to
 * choose the answer.
 */
@Suppress("CyclomaticComplexMethod") // One branch per refusal, in their order.
internal fun admissible(
    decider: DecideFunction<*>,
    command: Command<*>,
    loaded: CachedState,
    reach: CommandReach,
): CommandOutcome? {
    val version = loaded.version
    return when {
        version == 0 && decider.creation == Creation.NONE -> notFound(command)

        version > 0 && !reach.writes(loaded) ->
            if (decider.creation == Creation.NONE && !reach.reads(loaded)) notFound(command) else forbidden(command)

        loaded.deleted && !decider.onDeleted && command.body !is RestoreAggregate ->
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
 * A retry comes from a caller who could change the aggregate ([reach]), so the answer is given only to such a
 * caller: one who could not, reusing or guessing another caller's request id, is answered as if the aggregate did not
 * exist ([notFound]) and learns neither the stream nor which command committed it, so this check comes before the
 * command types are compared. By the default rule, the caller is checked on the aggregate as it was before the
 * stream, but the stream records only its scope after; the two differ only in a part the stream's own events
 * transferred, and that part is not checked: retrying a transfer is still answered though the caller no longer holds
 * the aggregate. Under an access policy, the caller is answered if it may read the aggregate by the attributes the
 * stream records ([CommandReach.retries]). A derived command is answered whatever its scope: its retry is the server
 * handling the same commit again, not a caller.
 */
internal fun duplicate(
    existing: EventStreamRecord,
    command: Command<*>,
    commandType: CommandType,
    reach: CommandReach,
    codec: EventCodec,
): CommandOutcome {
    val names = existing.events.mapTo(HashSet()) { it.name }
    val before = Scope(
        if (Lifecycle.OWNER_TRANSFERRED in names) command.scope.ownerId else existing.scope.ownerId,
        if (Lifecycle.SPACE_TRANSFERRED in names) command.scope.spaceId else existing.scope.spaceId,
    )
    return if (reach.retries(existing, before, codec)) settled(existing, commandType) else notFound(command)
}

/**
 * Refuses [command], decided on its aggregate at [version], once decided when the aggregate it leaves, in [scope]
 * with the declared access attributes [after], is out of its caller's reach ([reach]): when it creates the aggregate,
 * and, under an access policy, when it changes the declared attributes from [before]. The answer is
 * [FrameworkCodes.AGGREGATE_FORBIDDEN], and nothing is appended: a caller neither creates an aggregate nor moves one
 * out of what it may change. A transfer of the owner or the space changes no declared attribute: it gives the
 * aggregate away on purpose, and is checked before deciding only.
 */
internal fun leaves(
    command: Command<*>,
    reach: CommandReach,
    version: Int,
    scope: Scope,
    before: Map<String, Any?>,
    after: Map<String, Any?>,
): CommandOutcome? {
    // Neither a creation nor, under a policy, a change of the declared attributes: checked before deciding only.
    val unchanged = version > 0 && (!reach.governed || before == after)
    return if (unchanged || reach.writes(scope, after)) {
        null
    } else {
        rejected(
            FrameworkCodes.AGGREGATE_FORBIDDEN,
            "${command.ref} would be out of the reach of the command's caller."
        )
    }
}

/** The answer to a command on an aggregate that does not exist, or that its caller may not change: the same. */
internal fun notFound(command: Command<*>) =
    rejected(FrameworkCodes.AGGREGATE_NOT_FOUND, "${command.ref} does not exist.")

internal fun forbidden(command: Command<*>) =
    rejected(FrameworkCodes.AGGREGATE_FORBIDDEN, "The command's caller may not change ${command.ref}.")

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
