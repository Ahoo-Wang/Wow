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

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.event.CommandStartedEvent
import com.mongodb.event.CommandListener
import com.mongodb.reactivestreams.client.MongoClient
import com.mongodb.reactivestreams.client.MongoClients
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.PageQueryRequest
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.api.query.gateway.QueryPageSpec
import me.ahoo.wow.api.query.gateway.QueryProjection
import me.ahoo.wow.api.query.gateway.QueryResultShape
import me.ahoo.wow.tck.container.MongoTestFixture
import me.ahoo.wow.tck.query.backend.PortableContractKey
import me.ahoo.wow.tck.query.backend.PortableQueryDataset
import me.ahoo.wow.tck.query.backend.QueryBackendClientHold
import me.ahoo.wow.tck.query.backend.QueryBackendTestKit
import org.bson.Document
import org.bson.BsonDocument
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.test.StepVerifier
import java.util.concurrent.CopyOnWriteArrayList

class MongoQueryCommandSpec {
    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture("mongo_query_commands")

    private val commands = CopyOnWriteArrayList<Pair<String, BsonDocument>>()
    private lateinit var client: MongoClient
    private lateinit var fixture: MongoPortableQueryBackendFixture

    @BeforeEach
    fun setUp() {
        val settings = MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(mongo.connectionString))
            .addCommandListener(object : CommandListener {
                override fun commandStarted(event: CommandStartedEvent) {
                    commands += event.commandName to event.command.clone()
                }
            })
            .build()
        client = MongoClients.create(settings)
        fixture = MongoPortableQueryBackendFixture(client.getDatabase(mongo.databaseName), QueryDocumentKind.SNAPSHOT)
        fixture.initializeCollection()
        commands.clear()
    }

    @AfterEach
    fun tearDown() {
        client.close()
    }

    @Test
    fun `page uses one facet aggregate and no separate find or count`() {
        val testKit = testKit()
        val vector = PortableQueryDataset.vectors.single {
            it.key == PortableContractKey.Operation(QueryOperation.PAGE)
        }
        val request = PageQueryRequest(
            target = testKit.target,
            expression = vector.expression,
            resultShape = QueryResultShape.Dynamic,
            sort = vector.sort,
            page = QueryPageSpec(1, 2)
        )

        StepVerifier.create(testKit.gateway.page(request))
            .assertNext { page ->
                page.items.size.assert().isEqualTo(2)
                page.total.assert().isEqualTo(3L)
            }
            .verifyComplete()

        commands.count { it.first == "aggregate" }.assert().isOne()
        commands.count { it.first == "find" }.assert().isZero()
        commands.count { it.first == "count" }.assert().isZero()
        val pipeline = commands.single { it.first == "aggregate" }.second.getArray("pipeline")
        pipeline.count { it.asDocument().containsKey("\$facet") }.assert().isOne()
    }

    @Test
    fun `unlimited list omits driver limit and keeps bounded batch size`() {
        val testKit = testKit()
        val vector = PortableQueryDataset.vectors.single {
            it.key == PortableContractKey.Operation(QueryOperation.LIST)
        }
        val request = ListQueryRequest(
            target = testKit.target,
            expression = vector.expression,
            resultShape = QueryResultShape.Dynamic,
            sort = vector.sort,
            limit = 0
        )

        StepVerifier.create(testKit.gateway.list(request)).expectNextCount(3).verifyComplete()

        val find = commands.single { it.first == "find" }.second
        find.containsKey("limit").assert().isFalse()
        find.getNumber("batchSize").intValue().assert().isEqualTo(256)
    }

    @Test
    fun `partial decode failure cancels the real driver publisher`() {
        val factory = fixture.backendFactory.apply {
            reset()
            holdNextList(QueryBackendClientHold.AFTER_FIRST_RESULT)
        }
        val testKit = QueryBackendTestKit(factory, QueryDocumentKind.SNAPSHOT)
        val request = ListQueryRequest(
            target = testKit.target,
            expression = PortableQueryDataset.vectors.first().expression,
            resultShape = QueryResultShape.Typed(
                InvalidPortableResult::class.java,
                QueryProjection.Include(setOf(PortableQueryDataset.LOGICAL_ID))
            ),
            limit = 0
        )

        StepVerifier.create(testKit.gateway.list(request))
            .expectErrorSatisfies { error ->
                (error as QueryException).code.assert().isEqualTo(QueryErrorCode.BACKEND_FAILURE)
            }
            .verify()

        factory.subscriptionCount.assert().isOne()
        factory.cancellationCount.assert().isOne()
    }

    private fun testKit() = QueryBackendTestKit(
        fixture.backendFactory,
        QueryDocumentKind.SNAPSHOT,
        setOf(PortableQueryDataset.FULL_TEXT_CAPABILITY)
    )

    data class InvalidPortableResult(val logicalId: Int)
}
