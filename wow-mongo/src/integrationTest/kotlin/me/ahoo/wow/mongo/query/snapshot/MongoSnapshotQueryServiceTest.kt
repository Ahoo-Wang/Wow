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

package me.ahoo.wow.mongo.query.snapshot

import com.mongodb.reactivestreams.client.MongoClients
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.mongo.MongoSnapshotRepository
import me.ahoo.wow.mongo.SchemaInitializerSpec
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.tck.container.MongoLauncher
import me.ahoo.wow.tck.query.SnapshotQueryServiceSpec
import org.junit.jupiter.api.BeforeEach

class MongoSnapshotQueryServiceTest : SnapshotQueryServiceSpec() {
    lateinit var database: MongoDatabase

    @BeforeEach
    override fun setup() {
        val client = MongoClients.create(MongoLauncher.getConnectionString())
        database = client.getDatabase(SchemaInitializerSpec.DATABASE_NAME)
        super.setup()
    }

    override fun createSnapshotQueryServiceFactory(): SnapshotQueryServiceFactory {
        return MongoSnapshotQueryServiceFactory(database)
    }

    override fun createSnapshotRepository(): SnapshotRepository {
        return MongoSnapshotRepository(database)
    }
}
