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
package me.ahoo.wow.r2dbc

import com.fasterxml.jackson.databind.node.ObjectNode
import io.r2dbc.spi.Connection
import io.r2dbc.spi.R2dbcDataIntegrityViolationException
import io.r2dbc.spi.Statement
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.OwnerId.Companion.orDefaultOwnerId
import me.ahoo.wow.command.DuplicateRequestIdException
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.AbstractEventStore
import me.ahoo.wow.eventsourcing.EventVersionConflictException
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.event.FlatEventStreamRecord
import me.ahoo.wow.serialization.event.toEventStreamRecord
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObjectNode
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class R2dbcEventStore(
    private val database: Database,
    private val eventStreamSchema: EventStreamSchema
) : AbstractEventStore() {
    public override fun appendStream(eventStream: DomainEventStream): Mono<Void> {
        return Flux.usingWhen(
            /* resourceSupplier = */
            database.createConnection(eventStream.aggregateId),
            /* resourceClosure = */
            {
                val eventStreamRecord = JsonSerializer.valueToTree<ObjectNode>(eventStream)
                    .toEventStreamRecord()
                it.createStatement(eventStreamSchema.append(eventStream.aggregateId))
                    .bind(0, eventStreamRecord.id)
                    .bind(1, eventStream.aggregateId.id)
                    .bind(2, eventStream.aggregateId.tenantId)
                    .bind(3, eventStream.ownerId)
                    .bind(4, eventStreamRecord.requestId)
                    .bind(5, eventStreamRecord.commandId)
                    .bind(6, eventStreamRecord.version)
                    .bind(7, eventStreamRecord.header.toJsonString())
                    .bind(8, eventStreamRecord.body.toJsonString())
                    .bind(9, eventStream.size)
                    .bind(10, eventStreamRecord.createTime)
                    .execute()
            },
            /* asyncCleanup = */
            Connection::close,
        ).flatMap { it.rowsUpdated }
            .onErrorMap(R2dbcDataIntegrityViolationException::class.java) {
                return@onErrorMap when {
                    it.message!!.contains(eventStreamSchema.aggregateIdVersionUniqueIndexName) -> {
                        EventVersionConflictException(
                            eventStream = eventStream,
                            cause = it,
                        )
                    }

                    it.message!!.contains(eventStreamSchema.requestIdUniqueIndexName) -> {
                        DuplicateRequestIdException(
                            aggregateId = eventStream.aggregateId,
                            requestId = eventStream.requestId,
                            cause = it,
                        )
                    }

                    else -> {
                        it
                    }
                }
            }.then()
    }

    private fun load(aggregateId: AggregateId, statementSupplier: (Connection) -> Statement): Flux<DomainEventStream> {
        return Flux.usingWhen(
            database.createConnection(aggregateId),
            {
                statementSupplier(it).execute()
            },
            Connection::close,
        ).flatMap {
            it.map { readable ->
                val actualAggregateId = readable.get("aggregate_id", String::class.java)
                require(aggregateId.id == actualAggregateId)
                val id = checkNotNull(readable.get("id", String::class.java))
                val requestId = checkNotNull(readable.get("request_id", String::class.java))
                val tenantId = checkNotNull(readable.get("tenant_id", String::class.java))
                require(tenantId == aggregateId.tenantId) {
                    "The aggregated tenantId[${aggregateId.tenantId}] does not match the tenantId:[$tenantId] stored in the eventStore"
                }
                val ownerId = readable.get("owner_id", String::class.java).orDefaultOwnerId()
                val commandId = checkNotNull(readable.get("command_id", String::class.java))
                val version = checkNotNull(readable.get("version", Int::class.java))
                val header = checkNotNull(readable.get("header", String::class.java))
                val body = checkNotNull(readable.get("body", String::class.java))
                val createTime = checkNotNull(readable.get("create_time", Long::class.java))
                FlatEventStreamRecord(
                    id = id,
                    rawAggregateId = aggregateId,
                    ownerId = ownerId,
                    header = header.toObjectNode(),
                    body = body.toJsonNode(),
                    commandId = commandId,
                    requestId = requestId,
                    version = version,
                    createTime = createTime,
                ).toDomainEventStream()
            }
        }
    }

    public override fun loadStream(
        aggregateId: AggregateId,
        headVersion: Int,
        tailVersion: Int
    ): Flux<DomainEventStream> {
        return load(aggregateId) {
            it.createStatement(eventStreamSchema.load(aggregateId))
                .bind(0, aggregateId.id)
                .bind(1, headVersion)
                .bind(2, tailVersion)
        }
    }

    override fun loadStream(
        aggregateId: AggregateId,
        headEventTime: Long,
        tailEventTime: Long
    ): Flux<DomainEventStream> {
        return load(aggregateId) {
            it.createStatement(eventStreamSchema.loadByEventTime(aggregateId))
                .bind(0, aggregateId.id)
                .bind(1, headEventTime)
                .bind(2, tailEventTime)
        }
    }

    override fun last(aggregateId: AggregateId): Mono<DomainEventStream> {
        return load(aggregateId) {
            it.createStatement(eventStreamSchema.last(aggregateId))
                .bind(0, aggregateId.id)
        }.next()
    }
}
