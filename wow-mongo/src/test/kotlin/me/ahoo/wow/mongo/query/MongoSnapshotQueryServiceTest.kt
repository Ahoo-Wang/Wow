package me.ahoo.wow.mongo.query

import com.mongodb.reactivestreams.client.MongoClients
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.mongo.SchemaInitializerSpec
import me.ahoo.wow.query.Condition
import me.ahoo.wow.query.PagedQuery
import me.ahoo.wow.query.Query
import me.ahoo.wow.query.SnapshotQueryService
import me.ahoo.wow.tck.container.MongoLauncher
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class MongoSnapshotQueryServiceTest {

    lateinit var snapshotQueryService: SnapshotQueryService

    @BeforeEach
    fun init() {
        val client = MongoClients.create(MongoLauncher.getConnectionString())
        val database = client.getDatabase(SchemaInitializerSpec.DATABASE_NAME)
        snapshotQueryService =
            MongoSnapshotQueryServiceFactory(database).create(aggregateMetadata<MockCommandAggregate, MockStateAggregate>())
    }

    @Test
    fun query() {
        snapshotQueryService.query<MockStateAggregate>(
            tenantId = GlobalIdGenerator.generateAsString(),
            query = Query(condition = Condition.EMPTY, sort = emptyList())
        )
            .test()
            .verifyComplete()
    }

    @Test
    fun pagedQuery() {
        snapshotQueryService.pagedQuery<MockStateAggregate>(
            tenantId = GlobalIdGenerator.generateAsString(),
            pagedQuery = PagedQuery(condition = Condition.EMPTY, sort = emptyList())
        )
            .test()
            .consumeNextWith {
                assertThat(it.total, equalTo(0L))
            }
            .verifyComplete()
    }

    @Test
    fun count() {
        snapshotQueryService.count(
            tenantId = GlobalIdGenerator.generateAsString(),
            condition = Condition.EMPTY
        )
            .test()
            .expectNext(0L)
            .verifyComplete()
    }
}
