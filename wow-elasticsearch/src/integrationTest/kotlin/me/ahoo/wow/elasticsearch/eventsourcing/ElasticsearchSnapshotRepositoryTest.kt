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

package me.ahoo.wow.elasticsearch.eventsourcing

import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.elasticsearch.TemplateInitializer.initSnapshotTemplate
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.tck.container.ElasticsearchLauncher
import me.ahoo.wow.tck.eventsourcing.snapshot.SnapshotRepositorySpec
import org.junit.jupiter.api.BeforeAll

internal class ElasticsearchSnapshotRepositoryTest : SnapshotRepositorySpec() {
    companion object {
        @JvmStatic
        @BeforeAll
        fun waitLauncher() {
            ElasticsearchLauncher.isRunning
        }
    }

    override fun createSnapshotRepository(): SnapshotRepository {
        val elasticsearchClient = ReactiveElasticsearchClients.createReactiveElasticsearchClient()
        elasticsearchClient.initSnapshotTemplate()
        return ElasticsearchSnapshotRepository(
            elasticsearchClient = elasticsearchClient
        )
    }
}
