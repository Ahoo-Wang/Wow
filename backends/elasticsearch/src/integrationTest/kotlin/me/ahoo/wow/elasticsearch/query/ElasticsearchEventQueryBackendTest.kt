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

package me.ahoo.wow.elasticsearch.query

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.elasticsearch.ElasticsearchFixture
import me.ahoo.wow.elasticsearch.events.ElasticsearchEventSinks
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.schema.EventsQuerySchemas
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.tck.EventQueryBackendSpec
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord

/**
 * The events backend contract against Elasticsearch (decision D235): each test's fresh context is a fresh events
 * index, written by the event search Sink once its body fields are mapped from the test Manifest's event schemas (as
 * the aggregate's schema maps them when it is first loaded).
 */
class ElasticsearchEventQueryBackendTest : EventQueryBackendSpec() {
    private val queries = ElasticsearchEventQueries(ElasticsearchFixture.client, ElasticsearchFixture.names)

    override suspend fun store(streams: List<EventStreamRecord>) {
        val sink = ElasticsearchEventSinks.of(ElasticsearchFixture.store()).sinkOf(type.context, type.name)
        sink.prepare()
        QueryModelCompiler.of(events = EventsQuerySchemas(manifest))
            .compile(QuerySchemaContext(type, QueryModel.EVENTS), binding(type).storage)
            .schema()
        sink.write(streams.map(::recordOf))
    }

    override fun binding(type: AggregateType): QueryBackendBinding<QueryBackend> = queries.binding(type)

    companion object {
        /** [stream] as the sink subscription hands it to a Sink. */
        fun recordOf(stream: EventStreamRecord): StateEventRecord = StateEventRecord.of(
            stream.ref,
            stream.version,
            commandId = stream.commandId,
            requestId = stream.requestId,
            commandType = stream.commandType,
            commitTime = stream.commitTime,
            events = stream.events.map {
                StateEventPayload.of(it.name, it.revision, WowJson.mapper.readTree(it.payload))
            },
            scope = stream.scope,
            operator = stream.operator,
        )
    }
}
