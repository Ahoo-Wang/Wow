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
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.AbstractEventStore
import me.ahoo.wow.eventsourcing.EventVersionConflictException
import me.ahoo.wow.eventsourcing.RequestIdIdempotencyException
import me.ahoo.wow.serialization.FlatEventStreamRecord
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.asEventStreamRecord
import me.ahoo.wow.serialization.asJsonNode
import me.ahoo.wow.serialization.asJsonString
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class R2dbcEventStore(
    private val database: Database,
    private val eventStreamSchema: EventStreamSchema,
) : AbstractEventStore() {
    public override fun appendStream(eventStream: DomainEventStream): Mono<Void> {
        return Flux.usingWhen(
            /* resourceSupplier = */
            database.createConnection(eventStream.aggregateId),
            /* resourceClosure = */
            {
                val eventStreamRecord = JsonSerializer.valueToTree<ObjectNode>(eventStream)
                    .asEventStreamRecord()
                it.createStatement(eventStreamSchema.append(eventStream.aggregateId))
                    .bind(0, eventStreamRecord.id)
                    .bind(1, eventStream.aggregateId.id)
                    .bind(2, eventStream.aggregateId.tenantId)
                    .bind(3, eventStreamRecord.requestId)
                    .bind(4, eventStreamRecord.commandId)
                    .bind(5, eventStreamRecord.version)
                    .bind(6, eventStreamRecord.header.asJsonString())
                    .bind(7, eventStreamRecord.body.asJsonString())
                    .bind(8, eventStream.size)
                    .bind(9, eventStreamRecord.createTime)
                    .execute()
            },
            /* asyncCleanup = */
            Connection::close,
        ).flatMap { it.rowsUpdated }
            .onErrorMap(R2dbcDataIntegrityViolationException::class.java) {
                return@onErrorMap when {
                    it.message!!.contains(eventStreamSchema.aggregateIdVersionUniqueIndexName) -> {
                        EventVersionConflictException(
                            eventStream,
                            it,
                        )
                    }

                    it.message!!.contains(eventStreamSchema.requestIdUniqueIndexName) -> {
                        RequestIdIdempotencyException(
                            eventStream,
                            it,
                        )
                    }

                    else -> {
                        it
                    }
                }
            }.then()
    }

    public override fun loadStream(
        aggregateId: AggregateId,
        headVersion: Int,
        tailVersion: Int,
    ): Flux<DomainEventStream> {
        return Flux.usingWhen(
            database.createConnection(aggregateId),
            {
                it.createStatement(eventStreamSchema.load(aggregateId))
                    .bind(0, aggregateId.id)
                    .bind(1, headVersion)
                    .bind(2, tailVersion)
                    .execute()
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
                val commandId = checkNotNull(readable.get("command_id", String::class.java))
                val version = checkNotNull(readable.get("version", Int::class.java))
                val header = checkNotNull(readable.get("header", String::class.java))
                val body = checkNotNull(readable.get("body", String::class.java))
                val createTime = checkNotNull(readable.get("create_time", Long::class.java))
                FlatEventStreamRecord(
                    id = id,
                    rawAggregateId = aggregateId,
                    header = header.asJsonNode() as ObjectNode,
                    body = body.asJsonNode(),
                    commandId = commandId,
                    requestId = requestId,
                    version = version,
                    createTime = createTime
                ).asDomainEventStream()
            }
        }
    }
}
