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

import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.log.CreationClaim
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner

/**
 * The streams already committed for a request, and the claims of creations not committed yet: what admission reads
 * before it gives a creation command without an id one. None of its members has a default and it is no `fun
 * interface`, so that a lambda cannot stand for it: one that looked requests up and kept its claims to itself left
 * the node's own gateway drawing a new id for every attempt (#6518). Build one with [of].
 */
@InternalWowApi
interface CommittedRequests {
    /** The stream committed for [requestId] within [tenantId] in aggregate type [type], if any. */
    suspend fun find(type: AggregateType, tenantId: String, requestId: String): EventStreamRecord?

    /**
     * The claim of the aggregate the creation of [requestId] targets: [claim] when the request has none, else its
     * first ([EventLog.claimCreation]).
     */
    suspend fun claim(type: AggregateType, tenantId: String, requestId: String, claim: CreationClaim): CreationClaim

    /** The partitions of the log of [type]: 1 for a type without one. */
    fun partitions(type: AggregateType): Int

    @InternalWowApi
    companion object {
        /** Nothing is ever committed or claimed: every creation without an id takes a new one, of one partition. */
        @JvmField
        val NONE: CommittedRequests = object : CommittedRequests {
            override suspend fun find(type: AggregateType, tenantId: String, requestId: String): EventStreamRecord? =
                null

            override suspend fun claim(
                type: AggregateType,
                tenantId: String,
                requestId: String,
                claim: CreationClaim,
            ): CreationClaim = claim

            override fun partitions(type: AggregateType): Int = 1
        }

        /** The streams [log] holds ([EventLog.findByRequestId]) and the claims it keeps ([EventLog.claimCreation]). */
        @JvmStatic
        fun of(log: EventLog): CommittedRequests = of { log }

        /**
         * The streams and claims of each aggregate type's log, [logOf] it; a type with none (of a context the node
         * does not serve) has nothing committed or claimed, as [NONE].
         */
        @JvmStatic
        fun of(logOf: (AggregateType) -> EventLog?): CommittedRequests = object : CommittedRequests {
            override suspend fun find(type: AggregateType, tenantId: String, requestId: String): EventStreamRecord? =
                logOf(type)?.findByRequestId(type, tenantId, requestId)

            override suspend fun claim(
                type: AggregateType,
                tenantId: String,
                requestId: String,
                claim: CreationClaim,
            ): CreationClaim = logOf(type)?.claimCreation(type, tenantId, requestId, claim) ?: claim

            override fun partitions(type: AggregateType): Int = logOf(type)?.partitions ?: 1
        }
    }
}

/**
 * The ids admission gives the creation commands that name none: a command, a client's or one a saga derived, whose
 * request already committed takes the id of the aggregate it created, without drawing a new one, so a retry or a
 * replay keeps it. Any other takes a new one from its aggregate type's generator in [ids], so that every attempt of
 * the request meets the others in one partition, where every log's request key is unique:
 *
 * - aimed at the request's partition ([Partitioner.partitionOfRequest]) when the generator can aim
 *   ([me.ahoo.wow.spi.id.AggregateIdGenerator.nextIn], as Wow's UUIDv7 ids do): nothing is claimed, and an attempt that
 *   runs while an earlier one of its request is still deciding commits, or is answered as its duplicate, at the key;
 * - else drawn and claimed for its request ([CommittedRequests.claim]): the request's first claim holds, so every
 *   attempt targets the earlier one's aggregate, one partition, on a log whose key is narrower than the aggregate type
 *   (MongoDB's, within a partition); a log whose key spans the type keeps no claims, and its key meets them.
 *
 * A generator aims on every call or on none, so the attempts of one request take one path. Committed ids never
 * change. A retry still draws a number that is then not used: ids are stable, not gap-free.
 *
 * The committed or claimed request is reused only within the scope that sent it. A caller outside it gets a new id of
 * its own, in the partition of the request's aggregate, so that every log's request key meets it: the kernel refuses
 * it as for any duplicate, and it learns nothing of the aggregate the request created.
 */
@InternalWowApi
class CreationIds(
    private val ids: IdGenerators = IdGenerators.DEFAULT,
    private val committed: CommittedRequests,
) {
    /**
     * The id of the aggregate of [type] that a creation command of request [requestId] in [tenantId], in [scope],
     * targets: that of the stream its request committed, else a new one aimed at the request's partition, else that of
     * the request's claim, else a new one, claimed.
     * An aggregate of [ownership] checks only that part of the scope.
     */
    suspend fun idOf(
        type: AggregateType,
        tenantId: String,
        requestId: String,
        scope: Scope = Scope.NONE,
        ownership: Ownership = Ownership.OWNER,
    ): String {
        val caller = ownership.scopeOf(scope)
        val stream = committed.find(type, tenantId, requestId)
        if (stream != null) {
            return if (admits(stream.scope, caller)) stream.ref.id else besides(stream.ref)
        }
        val partitions = committed.partitions(type)
        val partition = Partitioner.partitionOfRequest(type, tenantId, requestId, partitions)
        ids.aggregates.nextIn(type, tenantId, partition, partitions)?.let { return it }
        val drawn = ids.aggregates.next(type)
        val claim = committed.claim(type, tenantId, requestId, CreationClaim(drawn, scope))
        return when {
            claim.aggregateId == drawn -> drawn
            admits(claim.scope, caller) -> claim.aggregateId
            else -> besides(type.ref(tenantId, claim.aggregateId))
        }
    }

    /**
     * A new id of [request]'s type in the partition of [request], the aggregate a request of another scope created or
     * claimed: aimed there, else drawn until one falls there, at most [MAX_DRAWS] times.
     */
    private fun besides(request: AggregateRef): String {
        val partitions = committed.partitions(request.type)
        val partition = Partitioner.partitionOf(request, partitions)
        ids.aggregates.nextIn(request.type, request.tenantId, partition, partitions)?.let { return it }
        repeat(MAX_DRAWS) {
            val id = ids.aggregates.next(request.type)
            if (Partitioner.partitionOf(request.type.ref(request.tenantId, id), partitions) == partition) {
                return id
            }
        }
        error(
            "The generator of ${request.type} gave no id in partition $partition of $MAX_DRAWS drawn, where the request " +
                "a caller of another scope reuses created ${request.type} already."
        )
    }

    /**
     * [command], a command of [model], with its aggregate id assigned when it names none
     * ([me.ahoo.wow.api.aggregate.AggregateRef.isAssigned]); fails for a command that does not create its aggregate.
     * An aggregate whose id is its owner's takes the command's owner, and fails for a command with none.
     */
    suspend fun <C : Any> assign(model: AggregateModel<*>, command: Command<C>): Command<C> {
        val ref = command.ref
        if (ref.isAssigned) {
            return command
        }
        if (model.idSource == AggregateIdSource.OWNER) {
            val owner = checkNotNull(command.scope.ownerId) {
                "The command ${command.id} names no ${ref.type}, whose id is its owner's, and has no owner to take it from."
            }
            return command.copyWith(ref = ref.type.ref(ref.tenantId, owner))
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
        /**
         * The ids [besides] draws at most: a generator that spreads its ids over the partitions misses one of
         * [Partitioner.SLOTS] partitions this often with a chance below e^-16.
         */
        const val MAX_DRAWS = 16 * Partitioner.SLOTS

        /** Whether a stream in scope [aggregate] answers a command in scope [caller]: each part the caller has matches. */
        fun admits(aggregate: Scope, caller: Scope): Boolean =
            (caller.ownerId == null || caller.ownerId == aggregate.ownerId) &&
                (caller.spaceId == null || caller.spaceId == aggregate.spaceId)
    }
}
