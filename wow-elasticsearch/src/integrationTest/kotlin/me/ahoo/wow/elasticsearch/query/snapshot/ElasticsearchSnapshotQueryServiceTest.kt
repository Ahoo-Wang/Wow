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

import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.elasticsearch.TemplateInitializer.initSnapshotTemplate
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import me.ahoo.wow.tck.query.SnapshotQueryServiceSpec
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient

class ElasticsearchSnapshotQueryServiceTest : SnapshotQueryServiceSpec() {
    @JvmField
    @RegisterExtension
    val elasticsearch = ElasticsearchTestFixture()

    lateinit var elasticsearchClient: ReactiveElasticsearchClient

    @BeforeEach
    override fun setup() {
        elasticsearchClient = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        elasticsearchClient.initSnapshotTemplate()
        super.setup()
    }

    override fun createSnapshotQueryServiceFactory(): SnapshotQueryServiceFactory {
        return ElasticsearchSnapshotQueryServiceFactory(elasticsearchClient)
    }

    override fun createSnapshotStore(): SnapshotStore {
        return ElasticsearchSnapshotStore(elasticsearchClient)
    }
}
