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

package me.ahoo.wow.mongo.query.backend

import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.tck.container.MongoTestFixture
import me.ahoo.wow.tck.query.backend.PortableQueryDataset
import me.ahoo.wow.tck.query.backend.ObservableQueryBackendFactory
import me.ahoo.wow.tck.query.backend.SnapshotQueryBackendSpec
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.core.publisher.Mono

class MongoSnapshotQueryBackendSpec : SnapshotQueryBackendSpec() {
    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture("mongo_snapshot_query_backend")

    private lateinit var fixture: MongoPortableQueryBackendFixture

    @BeforeEach
    fun initializeCollection() {
        fixture = MongoPortableQueryBackendFixture(mongo.database(), QueryDocumentKind.SNAPSHOT)
        fixture.initializeCollection()
    }

    override fun backendFactory(): ObservableQueryBackendFactory = fixture.backendFactory

    override fun prepare(dataset: PortableQueryDataset): Mono<Void> = fixture.prepare(dataset)

    override fun clear(): Mono<Void> = fixture.clear()

    override fun declaredCapabilities() = setOf(PortableQueryDataset.FULL_TEXT_CAPABILITY)
}
