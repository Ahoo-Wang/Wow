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

package me.ahoo.wow.elasticsearch

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.configuration.WowResourceLocator
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient

/**
 * Creates the snapshot indices (`wow.{context}.{aggregate}.snapshot`) whose definitions are shipped, behind
 * the deployment's index prefix.
 */
class ElasticsearchSnapshotIndexInitializer(
    elasticsearchClient: ReactiveElasticsearchClient,
    resourceLocator: WowResourceLocator = WowResourceLocator(),
    namedAggregates: Iterable<NamedAggregate> = MetadataSearcher.namedAggregateType.keys,
    indexNaming: ElasticsearchIndexNaming,
) : ElasticsearchIndexInitializer(elasticsearchClient, resourceLocator, namedAggregates, indexNaming) {
    /** The constructor from before the index prefix, kept for binary compatibility: Wow's unprefixed names. */
    constructor(
        elasticsearchClient: ReactiveElasticsearchClient,
        resourceLocator: WowResourceLocator = WowResourceLocator(),
        namedAggregates: Iterable<NamedAggregate> = MetadataSearcher.namedAggregateType.keys,
    ) : this(elasticsearchClient, resourceLocator, namedAggregates, ElasticsearchIndexNaming.DEFAULT)

    override val indexKind: String = "snapshot"

    override fun indexName(namedAggregate: NamedAggregate): String = namedAggregate.toSnapshotIndexName()
}
