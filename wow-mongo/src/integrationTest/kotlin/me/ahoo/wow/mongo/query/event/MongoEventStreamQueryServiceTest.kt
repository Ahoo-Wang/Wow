package me.ahoo.wow.mongo.query.event

import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.QueryResultShape
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.mongo.MongoEventStore
import me.ahoo.wow.mongo.query.MongoMandatoryTenantPolicy
import me.ahoo.wow.mongo.query.legacyMongoQueryGateway
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.tck.container.MongoTestFixture
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import me.ahoo.wow.tck.query.EventStreamQueryServiceSpec
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.time.Instant

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

    @Test
    fun `legacy facade preserves dynamic time while mandatory tenant policy matches direct gateway`() {
        val target = QueryTarget(namedAggregate, QueryDocumentKind.EVENT_STREAM)
        val policy = MongoMandatoryTenantPolicy(TenantId.DEFAULT_TENANT_ID)
        val gateway = legacyMongoQueryGateway(database, target, MOCK_AGGREGATE_METADATA, listOf(policy))
        val service = MongoEventStreamQueryServiceFactory(database, gateway).create(namedAggregate)
        val allowed = generateEventStream(namedAggregate.aggregateId(generateGlobalId(), TenantId.DEFAULT_TENANT_ID))
        val denied = generateEventStream(namedAggregate.aggregateId(generateGlobalId(), "denied-tenant"))
        eventStore.append(allowed).then(eventStore.append(denied)).block()

        Mono.zip(
            service.dynamicList(ListQuery(Condition.ALL)).collectList(),
            gateway.list(
                ListQueryRequest(
                    target = target,
                    expression = MatchAll,
                    resultShape = QueryResultShape.Typed(DynamicDocument::class.java),
                    limit = 10
                )
            ).collectList()
        ).test()
            .assertNext { results ->
                val legacy = results.t1.single()
                val direct = results.t2.single()
                legacy.keys.assert().isEqualTo(direct.keys)
                legacy.forEach { (field, value) ->
                    if (field == "createTime") {
                        value.assert().isInstanceOf(Long::class.javaObjectType)
                        value.assert().isEqualTo((direct[field] as Instant).toEpochMilli())
                    } else {
                        value.assert().isEqualTo(direct[field])
                    }
                }
                legacy["tenantId"].assert().isEqualTo(TenantId.DEFAULT_TENANT_ID)
                legacy["id"].assert().isEqualTo(allowed.id)
                policy.calls.get().assert().isEqualTo(2)
            }
            .verifyComplete()
    }
}
