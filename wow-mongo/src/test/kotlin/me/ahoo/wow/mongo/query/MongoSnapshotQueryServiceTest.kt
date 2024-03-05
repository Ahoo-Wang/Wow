package me.ahoo.wow.mongo.query

import com.mongodb.reactivestreams.client.MongoClients
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Query
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.mongo.SchemaInitializerSpec
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
        snapshotQueryService.single(
            condition = Condition.ALL
        )
            .test()
            .verifyComplete()
    }

    @Test
    fun query() {
        snapshotQueryService.query(
            query = Query(condition = Condition.ALL, sort = emptyList())
        )
            .test()
            .verifyComplete()
    }

    @Test
    fun pagedQuery() {
        snapshotQueryService.pagedQuery(
            pagedQuery = PagedQuery(condition = Condition.ALL, sort = emptyList())
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
            condition = Condition.ALL
        )
            .test()
            .expectNext(0L)
            .verifyComplete()
    }
}
