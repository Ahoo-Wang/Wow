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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.mongo.AggregateSchemaInitializer.createAggregateIdAndRequestIdUniqueIndex
import me.ahoo.wow.mongo.AggregateSchemaInitializer.createAggregateIdAndVersionUniqueIndex
import me.ahoo.wow.mongo.AggregateSchemaInitializer.createAggregateIdIndex
import me.ahoo.wow.mongo.AggregateSchemaInitializer.createOwnerIdIndex
import me.ahoo.wow.mongo.AggregateSchemaInitializer.createRequestIdUniqueIndex
import me.ahoo.wow.mongo.AggregateSchemaInitializer.createTenantIdIndex
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.container.MongoTestFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono

@Suppress("DEPRECATION")
class AggregateSchemaInitializerCompatibilityTest {
    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture()

    @AfterEach
    fun cleanup() {
        mongo.database().getCollection(COLLECTION_NAME).drop().toMono().block()
    }

    @Test
    fun `deprecated public index helpers reconcile their managed indexes`() {
        val collection = mongo.database().getCollection(COLLECTION_NAME)
        collection.createAggregateIdIndex()
        collection.createAggregateIdAndVersionUniqueIndex()
        collection.createRequestIdUniqueIndex()
        collection.createAggregateIdAndRequestIdUniqueIndex()
        collection.createTenantIdIndex()
        collection.createOwnerIdIndex()

        val indexes = collection.listIndexes().toFlux().collectList().block()!!
            .associateBy { it.getString("name") }
        indexes.keys.assert().contains(
            "${MessageRecords.AGGREGATE_ID}_hashed",
            AggregateSchemaInitializer.AGGREGATE_ID_AND_VERSION_UNIQUE_INDEX_NAME,
            AggregateSchemaInitializer.REQUEST_ID_UNIQUE_INDEX_NAME,
            "${MessageRecords.AGGREGATE_ID}_1_${MessageRecords.REQUEST_ID}_1",
            "${MessageRecords.TENANT_ID}_hashed",
            "${MessageRecords.OWNER_ID}_hashed",
        )
        indexes[AggregateSchemaInitializer.AGGREGATE_ID_AND_VERSION_UNIQUE_INDEX_NAME]
            ?.getBoolean("unique", false)
            .assert()
            .isTrue()
        indexes[AggregateSchemaInitializer.REQUEST_ID_UNIQUE_INDEX_NAME]
            ?.getBoolean("unique", false)
            .assert()
            .isTrue()
        indexes["${MessageRecords.AGGREGATE_ID}_1_${MessageRecords.REQUEST_ID}_1"]
            ?.getBoolean("unique", false)
            .assert()
            .isTrue()
    }

    companion object {
        private const val COLLECTION_NAME = "aggregate_schema_initializer_compatibility"
    }
}
