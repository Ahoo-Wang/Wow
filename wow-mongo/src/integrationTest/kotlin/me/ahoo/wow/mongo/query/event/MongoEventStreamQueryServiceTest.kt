package me.ahoo.wow.mongo.query.event

import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toEventStreamCollectionName
import me.ahoo.wow.mongo.MongoEventStore
import me.ahoo.wow.mongo.query.AbstractMongoFilterConverter
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.CursorTokenCodec
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.event.query
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
import me.ahoo.wow.tck.query.EventStreamQueryServiceSpec
import me.ahoo.wow.serialization.MessageRecords
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.core.publisher.Flux
import reactor.kotlin.test.test
import java.util.Base64

class MongoEventStreamQueryServiceTest : EventStreamQueryServiceSpec() {
    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture()

    lateinit var database: MongoDatabase
    private val cursorTokenCodec = CursorTokenCodec.fromBase64Url(
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() }),
    )

    @BeforeEach
    override fun setup() {
        database = mongo.database()
        super.setup()
    }

    override fun createEventStore(): EventStore {
        return MongoEventStore(database)
    }

    override fun createEventStreamQueryServiceFactory(): EventStreamQueryServiceFactory {
        return MongoEventStreamQueryServiceFactory(database, cursorTokenCodec = cursorTokenCodec)
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
    fun `public constructor should expose default event stream schema`() {
        val queryService = MongoEventStreamQueryService(
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
        val queryService = MongoEventStreamQueryService(
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

    @Test
    fun `strict aggregation should use explicit event payload schema`() {
        val tenantId = generateGlobalId()
        val eventStream = generateEventStream(
            namedAggregate.aggregateId(tenantId = tenantId),
            eventCount = 1,
            createdEventSupplier = { MockAggregateCreated("created") },
        )
        eventStore.append(eventStream).block()
        val queryService = MongoEventStreamQueryServiceFactory(
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
                row.getValue<String>("data").assert().isEqualTo("created")
                row.getValue<Long>("count").assert().isEqualTo(1L)
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
