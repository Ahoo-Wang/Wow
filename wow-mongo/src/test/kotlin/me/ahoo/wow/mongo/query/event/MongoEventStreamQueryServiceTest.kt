package me.ahoo.wow.mongo.query.event

import com.mongodb.reactivestreams.client.MongoClients
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.mongo.SchemaInitializerSpec
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.dynamicQuery
import me.ahoo.wow.query.event.query
import me.ahoo.wow.tck.container.MongoLauncher
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class MongoEventStreamQueryServiceTest {
    lateinit var queryService: EventStreamQueryService

    @BeforeEach
    fun init() {
        val client = MongoClients.create(MongoLauncher.getConnectionString())
        val database = client.getDatabase(SchemaInitializerSpec.DATABASE_NAME)
        queryService =
            MongoEventStreamQueryServiceFactory(database).create(aggregateMetadata<MockCommandAggregate, MockStateAggregate>())
    }

    @Test
    fun list() {
        listQuery {
            condition {
                tenantId(GlobalIdGenerator.generateAsString())
            }
        }.query(queryService)
            .test()
            .verifyComplete()
    }

    @Test
    fun dynamicList() {
        listQuery {
            condition {
                tenantId(GlobalIdGenerator.generateAsString())
            }
        }.dynamicQuery(queryService)
            .test()
            .verifyComplete()
    }
}
