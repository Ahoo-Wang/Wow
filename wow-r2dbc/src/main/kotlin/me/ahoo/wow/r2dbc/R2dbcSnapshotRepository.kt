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

import io.r2dbc.spi.Connection
import io.r2dbc.spi.Readable
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.infra.TypeNameMapper.asType
import me.ahoo.wow.modeling.annotation.asStateAggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregate.Companion.asStateAggregate
import me.ahoo.wow.serialization.asJsonString
import me.ahoo.wow.serialization.asObject
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class R2dbcSnapshotRepository(
    private val database: Database,
    private val snapshotSchema: SnapshotSchema
) : SnapshotRepository {

    override fun <S : Any> load(
        aggregateId: AggregateId
    ): Mono<Snapshot<S>> {
        return Flux.usingWhen(
            /* resourceSupplier = */
            database.createConnection(aggregateId),
            /* resourceClosure = */
            {
                it.createStatement(snapshotSchema.load(aggregateId))
                    .bind(0, aggregateId.id)
                    .execute()
            },
            Connection::close,
        )
            .flatMap {
                it.map { readable ->
                    mapSnapshot<S>(
                        aggregateId = aggregateId,
                        expectedVersion = null,
                        readable = readable,
                    )
                }
            }
            .next()
    }

    private fun <S : Any> mapSnapshot(
        aggregateId: AggregateId,
        expectedVersion: Int?,
        readable: Readable
    ): Snapshot<S> {
        val actualAggregateId = checkNotNull(readable.get("aggregate_id", String::class.java))
        require(aggregateId.id == actualAggregateId)
        val tenantId = checkNotNull(readable.get("tenant_id", String::class.java))
        require(tenantId == aggregateId.tenantId) {
            "The aggregated tenantId[${aggregateId.tenantId}] does not match the tenantId:[$tenantId] stored in the eventStore"
        }
        val actualVersion = checkNotNull(readable.get("version", Int::class.java))
        expectedVersion?.let {
            check(actualVersion == expectedVersion)
        }
        val lastEventId = readable.get("last_event_id", String::class.java).orEmpty()
        val firstEventTime = readable.get("first_event_time", Long::class.java) ?: 0L
        val lastEventTime = readable.get("last_event_time", Long::class.java) ?: 0L
        val snapshotTime = checkNotNull(readable.get("snapshot_time", Long::class.java))
        val metadata = checkNotNull(readable.get("state_type", String::class.java)).asType<S>()
            .asStateAggregateMetadata()
        val state = checkNotNull(readable.get("state", String::class.java))
        val stateRoot = state.asObject(metadata.aggregateType)
        val deleted = checkNotNull(readable.get("deleted", Boolean::class.java))
        return SimpleSnapshot(
            delegate = metadata.asStateAggregate(
                aggregateId = aggregateId,
                stateRoot = stateRoot,
                version = actualVersion,
                lastEventId = lastEventId,
                firstEventTime = firstEventTime,
                lastEventTime = lastEventTime,
                deleted = deleted
            ),
            snapshotTime = snapshotTime,
        )
    }

    override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> {
        return Flux.usingWhen(
            database.createConnection(snapshot.aggregateId),
            {
                it.createStatement(snapshotSchema.save(snapshot.aggregateId))
                    .bind(0, snapshot.aggregateId.id)
                    .bind(1, snapshot.aggregateId.tenantId)
                    .bind(2, snapshot.version)
                    .bind(3, snapshot.stateRoot.javaClass.name)
                    .bind(4, snapshot.stateRoot.asJsonString())
                    .bind(5, snapshot.lastEventId)
                    .bind(6, snapshot.firstEventTime)
                    .bind(7, snapshot.lastEventTime)
                    .bind(8, snapshot.snapshotTime)
                    .bind(9, snapshot.deleted)
                    .execute()
            },
            Connection::close,
        )
            .flatMap { it.rowsUpdated }
            .then()
    }

    /**
     * TODO
     */
    override fun scrollAggregateId(namedAggregate: NamedAggregate, cursorId: String, limit: Int): Flux<AggregateId> {
        throw UnsupportedOperationException()
    }
}
