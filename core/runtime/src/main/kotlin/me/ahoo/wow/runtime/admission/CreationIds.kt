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

package me.ahoo.wow.runtime.admission

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord

/**
 * The streams already committed for a request (decision D191): what admission looks up before it gives a creation
 * command without an id a new one.
 */
@InternalWowApi
fun interface CommittedRequests {
    /** The stream committed for [requestId] within [tenantId] in aggregate type [type], if any. */
    suspend fun find(type: AggregateType, tenantId: String, requestId: String): EventStreamRecord?

    @InternalWowApi
    companion object {
        /** Nothing is ever committed: every creation without an id takes a new one. */
        @JvmField
        val NONE: CommittedRequests = CommittedRequests { _, _, _ -> null }

        /** The streams [log] holds ([EventLog.findByRequestId]). */
        @JvmStatic
        fun of(log: EventLog): CommittedRequests =
            CommittedRequests { type, tenantId, requestId -> log.findByRequestId(type, tenantId, requestId) }
    }
}

/**
 * The ids admission gives the creation commands that name none (decisions D171, D191): a command, a client's or one a
 * saga derived, whose request already committed takes the id of the aggregate it created, without drawing a new one,
 * so a retry or a replay keeps it; any other takes a new one from its aggregate type's generator in [ids]. Committed
 * ids never change. A concurrent duplicate may still draw a number that is then not used: ids are stable, not gap-free.
 *
 * The committed request is reused only within the scope that sent it (decision D94): a caller outside it gets a new id
 * and is refused by the kernel as for any duplicate, so it learns nothing of the aggregate the request created.
 */
@InternalWowApi
class CreationIds(
    private val ids: IdGenerators = IdGenerators.DEFAULT,
    private val committed: CommittedRequests = CommittedRequests.NONE,
) {
    /**
     * The id of the aggregate of [type] that a creation command of request [requestId] in [tenantId], in [scope],
     * targets: that of the stream its request committed, else a new one. An aggregate of [ownership] checks only that
     * part of the scope (decision D189).
     */
    suspend fun idOf(
        type: AggregateType,
        tenantId: String,
        requestId: String,
        scope: Scope = Scope.NONE,
        ownership: Ownership = Ownership.OWNER,
    ): String {
        val stream = committed.find(type, tenantId, requestId)
        if (stream != null && admits(stream.scope, ownership.scopeOf(scope))) {
            return stream.ref.id
        }
        return ids.aggregates.next(type)
    }

    /**
     * [command], a command of [model], with its aggregate id assigned when it names none
     * ([me.ahoo.wow.api.aggregate.AggregateRef.isAssigned]); fails for a command that does not create its aggregate.
     */
    suspend fun <C : Any> assign(model: AggregateModel<*>, command: Command<C>): Command<C> {
        val ref = command.ref
        if (ref.isAssigned) {
            return command
        }
        val creation = model.decider(command.body.javaClass)?.creation
        check(creation != null && creation != Creation.NONE) {
            "The command ${command.id} names no ${ref.type} to act on: only a command that creates its aggregate may " +
                "leave its id to admission."
        }
        val id = idOf(ref.type, ref.tenantId, command.requestId, command.scope, model.ownership)
        return command.copyWith(ref = ref.type.ref(ref.tenantId, id))
    }

    private companion object {
        /** Whether a stream in scope [aggregate] answers a command in scope [caller]: each part the caller has matches. */
        fun admits(aggregate: Scope, caller: Scope): Boolean =
            (caller.ownerId == null || caller.ownerId == aggregate.ownerId) &&
                (caller.spaceId == null || caller.spaceId == aggregate.spaceId)
    }
}
