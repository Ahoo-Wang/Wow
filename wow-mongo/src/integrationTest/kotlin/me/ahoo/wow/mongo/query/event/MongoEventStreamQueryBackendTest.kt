package me.ahoo.wow.mongo.query.event

import com.mongodb.client.model.Filters
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
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
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.ResolvedQuery
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.dsl.filterExpression
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.event.EventStreamQueryBackend
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.event.NoOpEventStreamQueryBackend
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.QuerySchemaSourcePriority
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.schema.requireAccepted
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
import java.util.concurrent.atomic.AtomicInteger

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
        val schema = queryBackendBinding.schemaProvider.schema().block()!!

        schema.model.assert().isEqualTo(QueryModel.EVENT_STREAM)
        schema.fields.assert().containsKey(QueryField("body.name"))
        schema.fields.getValue(QueryField("body")).bindings.assert()
            .containsKey(QueryCapability.ELEMENT_SCOPE)
    }

    @Test
    fun `query helpers should prepare only on subscription`() {
        val querySchema = QueryModelSchema(QueryModel.EVENT_STREAM, emptySet(), emptyMap())
        val schemaCalls = AtomicInteger()
        val backend = object : EventStreamQueryBackend by NoOpEventStreamQueryBackend(namedAggregate) {}
        val schemaProvider = object : QueryModelSchemaProvider {
            override fun schema(): Mono<QueryModelSchema> {
                schemaCalls.incrementAndGet()
                return Mono.just(querySchema)
            }

            override fun refresh(): Mono<QueryModelSchema> = schema()
        }
        val binding = QueryBackendBinding(
            backend,
            schemaProvider,
        )

        val singlePublisher = singleQuery { }.query(binding)
        val aggregationPublisher = aggregation { count("count") }.query(binding)

        schemaCalls.get().assert().isZero()
        singlePublisher.thenMany(aggregationPublisher).test().verifyComplete()
        schemaCalls.get().assert().isEqualTo(2)
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
        }.query(queryBackendBinding)
            .test()
            .assertNext { node -> node.path("id").textValue().assert().isEqualTo(eventStream.id) }
            .verifyComplete()
    }

    @Test
    fun `identity exclusion should preserve event payload across query result shapes`() {
        val stream = generateEventStream(
            namedAggregate.aggregateId(generateGlobalId()),
            eventCount = 1,
            createdEventSupplier = { MockAggregateCreated("projected-event") },
        )
        eventStore.append(stream).block()
        val logicalId = "id"
        val filter = filterExpression { id(stream.id) }
        val payloadField = "body"
        val schema = queryBackendBinding.schemaProvider.schema().block()!!
        val projection = Projection(exclude = listOf(QueryField(logicalId)))
        val backend = queryBackendBinding.backend
        val single = SingleQuery(filter, projection)
        val list = ListQuery(filter, projection, limit = 1)
        val paged = PagedQuery(filter, projection, pagination = Pagination(size = 1))
        val results = listOf(
            backend.single(ResolvedQuery(schema.resolve(single).requireAccepted(QuerySchemaValidationMode.STRICT), schema))
                .map(::listOf),
            backend.list(ResolvedQuery(schema.resolve(list).requireAccepted(QuerySchemaValidationMode.STRICT), schema))
                .collectList(),
            backend.paged(ResolvedQuery(schema.resolve(paged).requireAccepted(QuerySchemaValidationMode.STRICT), schema))
                .map { page ->
                    page.total.assert().isEqualTo(1L)
                    page.list
                },
        )

        results.forEach { result ->
            result.test().assertNext { nodes ->
                val node = nodes.single()
                node.has(logicalId).assert().isFalse()
                node.has("_id").assert().isFalse()
                node.has(payloadField).assert().isTrue()
                node.path("body").isArray.assert().isTrue()
                node.path("body").size().assert().isEqualTo(1)
                node.path("body").path(0).path("body").path("data").asString().assert()
                    .isEqualTo("projected-event")
            }.verifyComplete()
        }
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
        ).create(namedAggregate)

        aggregation {
            filter { tenantId(tenantId) }
            expand("body")
            terms("body.data", "data")
            count("count")
        }.query(queryService, QuerySchemaValidationMode.STRICT)
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
                    QueryField("body.body.data") to QueryFieldDeclaration(
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

private fun ISingleQuery.query(
    binding: QueryBackendBinding<EventStreamQueryBackend>,
    mode: QuerySchemaValidationMode = QuerySchemaValidationMode.COMPATIBLE,
): Mono<ObjectNode> = Mono.defer { binding.schemaProvider.schema() }.flatMap { schema ->
    binding.backend.single(ResolvedQuery(schema.resolve(this).requireAccepted(mode), schema))
}

private fun AggregationQuery.query(
    binding: QueryBackendBinding<EventStreamQueryBackend>,
    mode: QuerySchemaValidationMode = QuerySchemaValidationMode.COMPATIBLE,
): Flux<ObjectNode> = Mono.defer { binding.schemaProvider.schema() }.flatMapMany { schema ->
    binding.backend.aggregate(ResolvedQuery(schema.resolve(this).requireAccepted(mode), schema))
}
