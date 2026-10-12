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

import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogPosition

/** How the kernel finished a command. */
sealed interface CommandOutcome {
    /**
     * The command's events are committed as [stream] at [position]; [state] is the state after them. [results] are
     * what its decide function put for the caller ([me.ahoo.wow.api.decide.DecideScope.putResult]); they are not
     * kept, so a duplicate of the command is answered without them.
     */
    data class Committed(
        val stream: EventStreamRecord,
        val position: LogPosition,
        val state: Any,
        val results: Map<String, Any> = emptyMap(),
    ) : CommandOutcome

    /** The same command was already committed as [stream]: it was not decided again. */
    data class AlreadyCommitted(val stream: EventStreamRecord) : CommandOutcome

    /** The command was accepted and changed nothing; the aggregate stays at [version]. */
    data class Unchanged(val version: Int) : CommandOutcome

    /** The command was refused; nothing was written. */
    data class Rejected(val rejection: Rejection) : CommandOutcome

    /** Concurrent writers kept winning the version slot for [attempts] attempts. */
    data class Contended(val attempts: Int) : CommandOutcome

    /** Whether the command committed could not be established. */
    data class Unknown(val cause: Throwable) : CommandOutcome
}

/** The rejection codes the kernel itself produces. */
object KernelRejections {
    const val UNHANDLED = "wow.command.unhandled"
    const val NOT_FOUND = "wow.aggregate.not-found"
    const val EXISTS = "wow.aggregate.exists"
    const val VERSION_MISMATCH = "wow.aggregate.version-mismatch"
    const val REQUEST_ID_REUSED = "wow.command.request-id-reused"

    /** The aggregate is deleted: it takes no command but [me.ahoo.wow.api.lifecycle.RestoreAggregate]. */
    const val DELETED = "wow.aggregate.deleted"
}
