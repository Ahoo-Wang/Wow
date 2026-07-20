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
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCheckpointCollectionName
import me.ahoo.wow.serialization.MessageRecords

class SnapshotCheckpointSchemaInitializerTest : SchemaInitializerSpec() {
    override fun initAllAggregateSchema(database: MongoDatabase) {
        SnapshotCheckpointSchemaInitializer(database).initAll()
    }

    override fun initAggregateSchema(database: MongoDatabase, namedAggregate: NamedAggregate) {
        SnapshotCheckpointSchemaInitializer(database).initSchema(namedAggregate)
    }

    override fun getCollectionName(namedAggregate: NamedAggregate): String =
        namedAggregate.toSnapshotCheckpointCollectionName()

    override fun getExpectedIndexNames(): Set<String> = setOf(
        "${MessageRecords.TENANT_ID}_1_${MessageRecords.AGGREGATE_ID}_1_${MessageRecords.VERSION}_1",
    )

    override fun getExpectedUniqueIndexNames(): Set<String> = getExpectedIndexNames()
}
