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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.elasticsearch.ElasticsearchIndexNaming
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotStore
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchQueryBackend
import me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE
import me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import java.time.Duration

class ElasticsearchSnapshotQueryBackend(
    override val namedAggregate: NamedAggregate,
    override val elasticsearchClient: ReactiveElasticsearchClient,
    override val queryBatchSize: Int = DEFAULT_SEARCH_BATCH_SIZE,
    override val queryKeepAlive: Duration = DEFAULT_PIT_KEEP_ALIVE,
    indexNaming: ElasticsearchIndexNaming,
) : AbstractElasticsearchQueryBackend(),
    SnapshotQueryBackend {
    /** The constructor from before the index prefix, kept for binary compatibility: Wow's unprefixed names. */
    constructor(
        namedAggregate: NamedAggregate,
        elasticsearchClient: ReactiveElasticsearchClient,
        queryBatchSize: Int = DEFAULT_SEARCH_BATCH_SIZE,
        queryKeepAlive: Duration = DEFAULT_PIT_KEEP_ALIVE,
    ) : this(namedAggregate, elasticsearchClient, queryBatchSize, queryKeepAlive, ElasticsearchIndexNaming.DEFAULT)

    override val name: String
        get() = ElasticsearchSnapshotStore.NAME
    override val indexName: String = indexNaming.snapshotIndexName(namedAggregate)
}
