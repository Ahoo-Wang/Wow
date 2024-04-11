package me.ahoo.wow.mongo.query

import com.mongodb.reactivestreams.client.MongoClients
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.mongo.SchemaInitializerSpec
import me.ahoo.wow.query.SnapshotQueryService
import me.ahoo.wow.query.condition
import me.ahoo.wow.query.count
import me.ahoo.wow.query.query
import me.ahoo.wow.query.singleQuery
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
    fun query() {
        query {
            condition {
                tenantId(GlobalIdGenerator.generateAsString())
            }
        }.query(snapshotQueryService)
            .test()
            .verifyComplete()
    }

    @Test
    fun pagedQuery() {
        me.ahoo.wow.query.pagedQuery {
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
    fun count() {
        condition {
            tenantId(GlobalIdGenerator.generateAsString())
        }.count(snapshotQueryService)
            .test()
            .expectNext(0L)
            .verifyComplete()
    }
}
