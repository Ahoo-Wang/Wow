package me.ahoo.wow.mongo.query.event

import com.mongodb.reactivestreams.client.MongoClients
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.mongo.MongoEventStore
import me.ahoo.wow.mongo.SchemaInitializerSpec
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.tck.container.MongoLauncher
import me.ahoo.wow.tck.query.EventStreamQueryServiceSpec
import org.junit.jupiter.api.BeforeEach

class MongoEventStreamQueryServiceTest : EventStreamQueryServiceSpec() {
    lateinit var database: MongoDatabase

    @BeforeEach
    override fun setup() {
        val client = MongoClients.create(MongoLauncher.getConnectionString())
        database = client.getDatabase(SchemaInitializerSpec.DATABASE_NAME)
        super.setup()
    }

    override fun createEventStore(): EventStore {
        return MongoEventStore(database)
    }

    override fun createEventStreamQueryServiceFactory(): EventStreamQueryServiceFactory {
        return MongoEventStreamQueryServiceFactory(database)
    }
}
