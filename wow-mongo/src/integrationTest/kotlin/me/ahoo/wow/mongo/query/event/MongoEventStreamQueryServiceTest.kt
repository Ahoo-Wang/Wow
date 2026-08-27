package me.ahoo.wow.mongo.query.event

import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.mongo.MongoEventStore
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.event.query
import me.ahoo.wow.query.event.requiredQueryModelSchemaProvider
import me.ahoo.wow.tck.container.MongoTestFixture
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import me.ahoo.wow.tck.query.EventStreamQueryServiceSpec
import me.ahoo.wow.serialization.MessageRecords
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.kotlin.test.test

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
        return MongoEventStreamQueryServiceFactory(database)
    }

    @Test
    fun `should provide event stream query schema`() {
        val schema = eventStreamQueryService.requiredQueryModelSchemaProvider().schema().block()!!

        schema.model.assert().isEqualTo(QueryModel.EVENT_STREAM)
        schema.fields.assert().containsKey(LogicalField("body.name"))
        schema.fields.getValue(LogicalField("body")).bindings.assert()
            .containsKey(QueryCapability.ELEMENT_SCOPE)
    }

    @Test
    fun `should query event stream by id in logical condition`() {
        val eventStream = generateEventStream(namedAggregate.aggregateId(tenantId = generateGlobalId()))
        eventStore.append(eventStream).block()

        singleQuery {
            condition {
                MessageRecords.ID eq eventStream.id
                tenantId(eventStream.aggregateId.tenantId)
            }
        }.query(eventStreamQueryService)
            .test()
            .expectNext(eventStream)
            .verifyComplete()
    }
}
