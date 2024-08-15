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
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.mongo.SchemaInitializerSpec
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.count
import me.ahoo.wow.query.snapshot.dynamicQuery
import me.ahoo.wow.query.snapshot.query
import me.ahoo.wow.tck.container.MongoLauncher
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class MongoSnapshotQueryServiceTest {

    lateinit var snapshotQueryService: SnapshotQueryService<MockStateAggregate>

    @BeforeEach
    fun init() {
        val client = MongoClients.create(MongoLauncher.getConnectionString())
        val database = client.getDatabase(SchemaInitializerSpec.DATABASE_NAME)
        snapshotQueryService =
            MongoSnapshotQueryServiceFactory(database).create(aggregateMetadata<MockCommandAggregate, MockStateAggregate>())
    }

    @Test
    fun single() {
        singleQuery {
            condition {
                tenantId(GlobalIdGenerator.generateAsString())
            }
        }.query(snapshotQueryService)
            .test()
            .verifyComplete()
    }

    @Test
    fun dynamicSingle() {
        singleQuery {
            condition {
                tenantId(GlobalIdGenerator.generateAsString())
            }
        }.dynamicQuery(snapshotQueryService)
            .test()
            .verifyComplete()
    }

    @Test
    fun query() {
        listQuery {
            condition {
                tenantId(GlobalIdGenerator.generateAsString())
            }
        }.query(snapshotQueryService)
            .test()
            .verifyComplete()
    }

    @Test
    fun dynamicList() {
        listQuery {
            condition {
                tenantId(GlobalIdGenerator.generateAsString())
            }
        }.dynamicQuery(snapshotQueryService)
            .test()
            .verifyComplete()
    }

    @Test
    fun paged() {
        pagedQuery {
            condition {
                tenantId(GlobalIdGenerator.generateAsString())
            }
        }.query(snapshotQueryService)
            .test()
            .consumeNextWith {
                assertThat(it.total, equalTo(0L))
            }
            .verifyComplete()
    }

    @Test
    fun dynamicPaged() {
        pagedQuery {
            condition {
                tenantId(GlobalIdGenerator.generateAsString())
            }
        }.dynamicQuery(snapshotQueryService)
            .test()
            .consumeNextWith {
                assertThat(it.total, equalTo(0L))
            }
            .verifyComplete()
    }

    @Test
    fun count() {
        condition {
            tenantId(GlobalIdGenerator.generateAsString())
        }.count(snapshotQueryService)
            .test()
            .expectNext(0L)
            .verifyComplete()
    }
}
