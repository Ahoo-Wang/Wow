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

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.test.asserts.assert
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCollectionName
import me.ahoo.wow.mongo.MongoSnapshotStore
import me.ahoo.wow.query.dsl.aggregationQuery
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.aggregate
import me.ahoo.wow.tck.container.MongoTestFixture
import me.ahoo.wow.tck.query.SnapshotQueryServiceSpec
import org.bson.types.Decimal128
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.math.BigDecimal

class MongoSnapshotQueryServiceTest : SnapshotQueryServiceSpec() {
    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture()

    lateinit var database: MongoDatabase

    @BeforeEach
    override fun setup() {
        database = mongo.database()
        super.setup()
    }

    override fun createSnapshotQueryServiceFactory(): SnapshotQueryServiceFactory {
        return MongoSnapshotQueryServiceFactory(database)
    }

    override fun createSnapshotStore(): SnapshotStore {
        return MongoSnapshotStore(database)
    }

    @Test
    fun `decimal terms should return portable double keys`() {
        database.getCollection(snapshot.aggregateId.toSnapshotCollectionName())
            .updateOne(
                Filters.eq("_id", snapshot.aggregateId.id),
                Updates.set("state.amount", Decimal128(BigDecimal("1.25"))),
            ).toMono()
            .test()
            .expectNextCount(1)
            .verifyComplete()

        aggregationQuery {
            groupBy("state.amount", "amount")
            count("count")
        }.aggregate(snapshotQueryService)
            .test()
            .consumeNextWith { result -> result["amount"].assert().isEqualTo(1.25) }
            .verifyComplete()
    }
}
