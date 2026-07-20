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

package me.ahoo.wow.mongo

import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCheckpointCollectionName
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCollectionName
import me.ahoo.wow.mongo.Documents.ID_FIELD
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import org.junit.jupiter.api.Test
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono

class SnapshotSchemaInitializerTest : SchemaInitializerSpec() {

    override fun initAllAggregateSchema(database: MongoDatabase) {
        SnapshotSchemaInitializer(database).initAll()
    }

    override fun initAggregateSchema(database: MongoDatabase, namedAggregate: NamedAggregate) {
        SnapshotSchemaInitializer(database).initSchema(namedAggregate)
    }

    override fun getCollectionName(namedAggregate: NamedAggregate): String {
        return namedAggregate.toSnapshotCollectionName()
    }

    override fun getExpectedIndexNames(): Set<String> = setOf(
        "${MessageRecords.TENANT_ID}_hashed",
        "${MessageRecords.OWNER_ID}_hashed",
        "${ID_FIELD}_hashed",
        "${StateAggregateRecords.DELETED}_hashed",
    )

    @Test
    fun `should not initialize checkpoint schema when initializing latest snapshots`() {
        val database = mongo.database()
        val namedAggregate = MaterializedNamedAggregate("", "testCheckpointDisabled")
        val checkpointCollectionName = namedAggregate.toSnapshotCheckpointCollectionName()
        database.getCollection(checkpointCollectionName).drop().toMono().block()

        SnapshotSchemaInitializer(database).initSchema(namedAggregate)

        database.listCollectionNames()
            .toFlux()
            .collectList()
            .block()!!
            .contains(checkpointCollectionName)
            .assert()
            .isFalse()
        database.getCollection(namedAggregate.toSnapshotCollectionName()).drop().toMono().block()
    }
}
