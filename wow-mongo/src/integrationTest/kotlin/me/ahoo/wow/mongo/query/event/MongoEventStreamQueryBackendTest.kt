package me.ahoo.wow.mongo.query.event

import com.mongodb.client.model.Filters
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toEventStreamCollectionName
import me.ahoo.wow.mongo.MongoEventStore
import me.ahoo.wow.mongo.query.AbstractMongoFilterConverter
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.event.EventStreamQueryBackend
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.event.requiredQueryModelSchemaProvider
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.QuerySchemaSourcePriority
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.tck.container.MongoTestFixture
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.query.EventStreamQueryBackendSpec
import me.ahoo.wow.serialization.MessageRecords
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.bson.Document
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import tools.jackson.databind.node.ObjectNode

class MongoEventStreamQueryBackendTest : EventStreamQueryBackendSpec() {

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

    override fun createEventStreamQueryBackendFactory(): EventStreamQueryBackendFactory {
        return MongoEventStreamQueryBackendFactory(database)
    }

    override fun prepareNullAndMissingCursorEventStreams(
        nullStream: DomainEventStream,
        missingStream: DomainEventStream,
    ) {
        val collection = database.getCollection(namedAggregate.toEventStreamCollectionName())
        collection.updateOne(
            Filters.eq("_id", nullStream.id),
            Document("\$set", Document().append("ownerId", null)),
        ).toMono().test().expectNextCount(1).verifyComplete()
        collection.updateOne(
            Filters.eq("_id", missingStream.id),
            Document("\$unset", Document("ownerId", "")),
        ).toMono().test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `should provide event stream query schema`() {
        val schema = eventStreamQueryBackend.requiredQueryModelSchemaProvider().schema().block()!!

        schema.model.assert().isEqualTo(QueryModel.EVENT_STREAM)
        schema.fields.assert().containsKey(LogicalField("body.name"))
        schema.fields.getValue(LogicalField("body")).bindings.assert()
            .containsKey(QueryCapability.ELEMENT_SCOPE)
    }

    @Test
    fun `public constructor should expose default event stream schema`() {
        val queryService = MongoEventStreamQueryBackend(
            namedAggregate,
            database.getCollection(namedAggregate.toEventStreamCollectionName()),
        )

        queryService.schema().test()
            .assertNext { schema -> schema.model.assert().isEqualTo(QueryModel.EVENT_STREAM) }
            .verifyComplete()
    }

    @Test
    fun `custom filter converter should make schema unavailable`() {
        val converter = object : AbstractMongoFilterConverter() {
            override val fieldConverter = EventStreamFieldConverter
        }
        val queryService = MongoEventStreamQueryBackend(
            namedAggregate,
            database.getCollection(namedAggregate.toEventStreamCollectionName()),
            converter,
        )

        queryService.schema().test()
            .expectError(QuerySchemaUnavailableException::class.java)
            .verify()
        queryService.refresh().test()
            .expectError(QuerySchemaUnavailableException::class.java)
            .verify()
    }

    @Test
    fun `should query event stream by id in logical filter`() {
        val eventStream = generateEventStream(namedAggregate.aggregateId(tenantId = generateGlobalId()))
        eventStore.append(eventStream).block()

        singleQuery {
            filter {
                MessageRecords.ID eq eventStream.id
                tenantId(eventStream.aggregateId.tenantId)
            }
        }.query(eventStreamQueryBackend)
            .test()
            .assertNext { node -> node.path("id").textValue().assert().isEqualTo(eventStream.id) }
            .verifyComplete()
    }

    @Test
    fun `strict aggregation should use explicit event payload schema`() {
        val tenantId = generateGlobalId()
        val eventStream = generateEventStream(
            namedAggregate.aggregateId(tenantId = tenantId),
            eventCount = 1,
            createdEventSupplier = { MockAggregateCreated("created") },
        )
        eventStore.append(eventStream).block()
        val queryService = MongoEventStreamQueryBackendFactory(
            database,
            listOf(eventPayloadSource()),
            QuerySchemaValidationMode.STRICT,
        ).create(namedAggregate)

        aggregation {
            filter { tenantId(tenantId) }
            expand("body")
            terms("body.data", "data")
            count("count")
        }.query(queryService)
            .test()
            .assertNext { row ->
                row.path("data").textValue().assert().isEqualTo("created")
                row.path("count").longValue().assert().isEqualTo(1L)
            }
            .verifyComplete()
    }

    private fun eventPayloadSource(): QuerySchemaSource = object : QuerySchemaSource {
        override val priority: Int = QuerySchemaSourcePriority.BEAN

        override fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> = Flux.just(
            QuerySchemaDeclaration(
                mapOf(
                    LogicalField("body.body.data") to QueryFieldDeclaration(
                        valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)),
                        nullable = DeclarationValue.Set(false),
                        required = DeclarationValue.Set(true),
                        cardinality = DeclarationValue.Set(QueryCardinality.SINGLE),
                    ),
                ),
            ),
        )
    }
}

private fun ISingleQuery.query(backend: EventStreamQueryBackend): Mono<ObjectNode> = backend.single(this)
private fun AggregationQuery.query(backend: EventStreamQueryBackend): Flux<ObjectNode> = backend.aggregate(this)
