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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.elasticsearch.IndexNameConverter.toEventStreamIndexName
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchConditionConverter
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchQueryService
import me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE
import me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.serialization.convert
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import java.time.Duration

class ElasticsearchEventStreamQueryService(
    override val namedAggregate: NamedAggregate,
    override val elasticsearchClient: ReactiveElasticsearchClient,
    override val conditionConverter: AbstractElasticsearchConditionConverter = EventStreamConditionConverter
) : AbstractElasticsearchQueryService<DomainEventStream>(), EventStreamQueryService {
    private var configuredQueryBatchSize: Int = DEFAULT_SEARCH_BATCH_SIZE
    private var configuredQueryKeepAlive: Duration = DEFAULT_PIT_KEEP_ALIVE

    constructor(
        namedAggregate: NamedAggregate,
        elasticsearchClient: ReactiveElasticsearchClient,
        conditionConverter: AbstractElasticsearchConditionConverter,
        queryBatchSize: Int,
        queryKeepAlive: Duration,
    ) : this(namedAggregate, elasticsearchClient, conditionConverter) {
        configuredQueryBatchSize = queryBatchSize
        configuredQueryKeepAlive = queryKeepAlive
    }

    override val indexName: String = namedAggregate.toEventStreamIndexName()
    protected override val queryBatchSize: Int
        get() = configuredQueryBatchSize
    protected override val queryKeepAlive: Duration
        get() = configuredQueryKeepAlive

    override fun toTypedResult(document: DynamicDocument): DomainEventStream {
        return document.convert<DomainEventStream>()
    }
}
