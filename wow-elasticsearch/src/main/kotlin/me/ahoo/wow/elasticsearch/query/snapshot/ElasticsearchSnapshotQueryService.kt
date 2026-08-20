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

package me.ahoo.wow.elasticsearch.query.snapshot

import co.elastic.clients.elasticsearch._types.query_dsl.Query
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotStore
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchQueryService
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.converter.ConditionConverter
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.convert
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient

class ElasticsearchSnapshotQueryService<S : Any> :
    AbstractElasticsearchQueryService<MaterializedSnapshot<S>>,
    SnapshotQueryService<S> {
    override val namedAggregate: NamedAggregate
    override val elasticsearchClient: ReactiveElasticsearchClient
    override val conditionConverter: ConditionConverter<Query>

    @Deprecated("Use the constructor that requires QueryGateway.")
    constructor(
        namedAggregate: NamedAggregate,
        elasticsearchClient: ReactiveElasticsearchClient,
        conditionConverter: ConditionConverter<Query> = SnapshotConditionConverter
    ) : super() {
        this.namedAggregate = namedAggregate
        this.elasticsearchClient = elasticsearchClient
        this.conditionConverter = conditionConverter
    }

    constructor(
        namedAggregate: NamedAggregate,
        elasticsearchClient: ReactiveElasticsearchClient,
        queryGateway: QueryGateway
    ) : super(namedAggregate, queryGateway, QueryDocumentKind.SNAPSHOT) {
        this.namedAggregate = namedAggregate
        this.elasticsearchClient = elasticsearchClient
        this.conditionConverter = SnapshotConditionConverter
    }

    override val name: String
        get() = ElasticsearchSnapshotStore.NAME
    override val indexName: String
        get() = namedAggregate.toSnapshotIndexName()
    private val snapshotType by lazy {
        JsonSerializer.typeFactory.constructParametricType(
            MaterializedSnapshot::class.java,
            namedAggregate.requiredAggregateType<Any>().aggregateMetadata<Any, S>().state.aggregateType,
        )
    }

    override fun toTypedResult(document: DynamicDocument): MaterializedSnapshot<S> {
        return document.convert(snapshotType)
    }
}
