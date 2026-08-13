package me.ahoo.wow.mongo.query.event

import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.mongo.MongoEventStore
import me.ahoo.wow.mongo.query.legacyMongoQueryGateway
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.tck.container.MongoTestFixture
import me.ahoo.wow.tck.query.EventStreamQueryServiceSpec
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.RegisterExtension

class MongoEventStreamQueryServiceTest : EventStreamQueryServiceSpec() {
    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture()

    lateinit var database: MongoDatabase

    @BeforeEach
    override fun setup() {
        database = mongo.database()
        super.setup()
    }

    override fun createEventStore(): EventStore {
        return MongoEventStore(database)
    }

    override fun createEventStreamQueryServiceFactory(): EventStreamQueryServiceFactory {
        val gateway = legacyMongoQueryGateway(
            database,
            QueryTarget(namedAggregate, QueryDocumentKind.EVENT_STREAM),
            MOCK_AGGREGATE_METADATA
        )
        return MongoEventStreamQueryServiceFactory(database, gateway)
    }
}
