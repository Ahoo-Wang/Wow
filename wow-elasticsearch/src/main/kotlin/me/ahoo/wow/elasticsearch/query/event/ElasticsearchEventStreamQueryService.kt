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

package me.ahoo.wow.elasticsearch.query.event

import co.elastic.clients.elasticsearch._types.query_dsl.Query
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.elasticsearch.IndexNameConverter.toEventStreamIndexName
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchQueryService
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryService
import me.ahoo.wow.query.converter.ConditionConverter
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.GatewayEventStreamQueryService
import me.ahoo.wow.serialization.convert
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient

class ElasticsearchEventStreamQueryService private constructor(
    override val namedAggregate: NamedAggregate,
    override val elasticsearchClient: ReactiveElasticsearchClient,
    override val conditionConverter: ConditionConverter<Query>,
    queryService: QueryService<DomainEventStream>?
) : AbstractElasticsearchQueryService<DomainEventStream>(queryService), EventStreamQueryService {
    @Deprecated("Use the constructor that requires QueryGateway.")
    constructor(
        namedAggregate: NamedAggregate,
        elasticsearchClient: ReactiveElasticsearchClient,
        conditionConverter: ConditionConverter<Query> = EventStreamConditionConverter
    ) : this(namedAggregate, elasticsearchClient, conditionConverter, null)

    constructor(
        namedAggregate: NamedAggregate,
        elasticsearchClient: ReactiveElasticsearchClient,
        queryGateway: QueryGateway
    ) : this(
        namedAggregate,
        elasticsearchClient,
        EventStreamConditionConverter,
        GatewayEventStreamQueryService(namedAggregate, queryGateway)
    )
    override val indexName: String = namedAggregate.toEventStreamIndexName()

    override fun toTypedResult(document: DynamicDocument): DomainEventStream {
        return document.convert<DomainEventStream>()
    }
}
