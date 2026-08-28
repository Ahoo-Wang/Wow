package me.ahoo.wow.spring.boot.starter.query

import io.mockk.every
import io.mockk.spyk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.abac.AbacTags
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.exception.WowException
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.event.NoOpEventStreamQueryServiceFactory
import me.ahoo.wow.query.mask.EventStreamDynamicDocumentMasker
import me.ahoo.wow.query.mask.StateDynamicDocumentMasker
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryService
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.filter.AbacQueryFilter
import me.ahoo.wow.query.snapshot.filter.MaskingSnapshotQueryFilter
import me.ahoo.wow.query.snapshot.filter.TailSnapshotQueryFilter
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import reactor.util.context.ContextView

class QueryAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `should load context with query handler beans`() {
        contextRunner
            .enableWow()
            .withUserConfiguration(QueryAutoConfiguration::class.java)
            .withBean(StateDynamicDocumentMasker::class.java, {
                spyk<StateDynamicDocumentMasker> {
                    every { namedAggregate } returns MOCK_AGGREGATE_METADATA
                }
            })
            .withBean(EventStreamDynamicDocumentMasker::class.java, {
                spyk<EventStreamDynamicDocumentMasker> {
                    every { namedAggregate } returns MOCK_AGGREGATE_METADATA
                }
            })
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasBean(ExistsBeanName.SNAPSHOT_QUERY_SERVICE)
                    .hasBean(ExistsBeanName.EVENT_STREAM_QUERY_SERVICE)
                    .hasBean("noOpSnapshotQueryServiceFactory")
                    .hasBean("noOpEventStreamQueryServiceFactory")
                    .hasSingleBean(MaskingSnapshotQueryFilter::class.java)
                    .hasSingleBean(TailSnapshotQueryFilter::class.java)
                    .hasBean("snapshotQueryFilterChain")
                    .hasBean("eventStreamQueryFilterChain")
                    .hasBean("snapshotQueryErrorHandler")
                    .hasBean("eventStreamQueryErrorHandler")
                    .hasSingleBean(SnapshotQueryGateway::class.java)
                    .hasSingleBean(EventStreamQueryGateway::class.java)

                context.getBean(
                    ExistsBeanName.SNAPSHOT_QUERY_SERVICE,
                    SnapshotQueryService::class.java,
                ).count(MatchAllFilter)
                    .test()
                    .expectErrorSatisfies(::assertUnavailable)
                    .verify()
                context.getBean(
                    ExistsBeanName.EVENT_STREAM_QUERY_SERVICE,
                    EventStreamQueryService::class.java,
                ).count(MatchAllFilter)
                    .test()
                    .expectErrorSatisfies(::assertUnavailable)
                    .verify()
            }
    }

    @Test
    fun `should load context when snapshot query service bean already exists`() {
        contextRunner
            .enableWow()
            .withUserConfiguration(QueryAutoConfiguration::class.java)
            .withBean(ExistsBeanName.SNAPSHOT_QUERY_SERVICE, ExistsBeanName::class.java)
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasBean("example.order.SnapshotQueryService")
                    .hasSingleBean(ExistsBeanName::class.java)
            }
    }

    @Test
    fun `should preserve explicitly configured no op query services`() {
        contextRunner
            .enableWow()
            .withUserConfiguration(QueryAutoConfiguration::class.java)
            .withBean(SnapshotQueryServiceFactory::class.java, { NoOpSnapshotQueryServiceFactory })
            .withBean(EventStreamQueryServiceFactory::class.java, { NoOpEventStreamQueryServiceFactory })
            .run { context: AssertableApplicationContext ->
                context.getBean(
                    ExistsBeanName.SNAPSHOT_QUERY_SERVICE,
                    SnapshotQueryService::class.java,
                ).count(MatchAllFilter)
                    .test()
                    .expectNext(0L)
                    .verifyComplete()
                context.getBean(
                    ExistsBeanName.EVENT_STREAM_QUERY_SERVICE,
                    EventStreamQueryService::class.java,
                ).count(MatchAllFilter)
                    .test()
                    .expectNext(0L)
                    .verifyComplete()
            }
    }

    @Test
    fun `injected query service should enforce policies while factory remains raw`() {
        contextRunner
            .enableWow()
            .withUserConfiguration(QueryAutoConfiguration::class.java)
            .withBean(RecordingSnapshotQueryServiceFactory::class.java, { RecordingSnapshotQueryServiceFactory() })
            .withBean(AbacQueryFilter::class.java, { TestAbacQueryFilter })
            .withBean(StateDynamicDocumentMasker::class.java, { TestStateDynamicDocumentMasker })
            .run { context: AssertableApplicationContext ->
                val factory = context.getBean(RecordingSnapshotQueryServiceFactory::class.java)
                val queryService = context.getBean(
                    ExistsBeanName.SNAPSHOT_QUERY_SERVICE,
                    SnapshotQueryService::class.java,
                )
                queryService.name.assert().isEqualTo(RAW_SERVICE_NAME)
                queryService.namedAggregate.assert().isEqualTo(factory.service.namedAggregate)

                val query = me.ahoo.wow.query.dsl.singleQuery { }
                queryService.dynamicSingle(query)
                    .test()
                    .consumeNextWith {
                        it.getNestedDocument("state").assert().doesNotContainKey(SECRET)
                    }
                    .verifyComplete()
                factory.service.lastQuery!!.filter.operator.assert()
                    .isNotEqualTo(me.ahoo.wow.api.query.FilterOperator.MATCH_ALL)

                val rawService = factory.create<Any>(MOCK_AGGREGATE_METADATA)
                rawService.assert().isSameAs(factory.service)
                rawService.assert().isNotSameAs(queryService)
                rawService.dynamicSingle(query)
                    .test()
                    .consumeNextWith {
                        it.getNestedDocument("state").getValue<String>(SECRET).assert().isEqualTo(RAW_SECRET)
                    }
                    .verifyComplete()
                factory.service.lastQuery.assert().isSameAs(query)
            }
    }

    private fun assertUnavailable(error: Throwable) {
        error.assert().isInstanceOf(WowException::class.java)
        (error as WowException).errorCode.assert().isEqualTo(ErrorCodes.INTERNAL_SERVER_ERROR)
        error.message.assert().contains("No query backend is configured")
    }

    internal class RecordingSnapshotQueryServiceFactory : SnapshotQueryServiceFactory {
        val service = RecordingSnapshotQueryService()

        @Suppress("UNCHECKED_CAST")
        override fun <S : Any> create(namedAggregate: NamedAggregate): SnapshotQueryService<S> =
            service as SnapshotQueryService<S>
    }

    internal class RecordingSnapshotQueryService : SnapshotQueryService<Any> by
    NoOpSnapshotQueryService(MOCK_AGGREGATE_METADATA) {
        override val name: String = RAW_SERVICE_NAME
        var lastQuery: ISingleQuery? = null

        override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> {
            lastQuery = singleQuery
            return Mono.fromSupplier {
                mutableMapOf<String, Any?>(
                    "state" to mutableMapOf<String, Any?>(SECRET to RAW_SECRET),
                ).toDynamicDocument()
            }
        }
    }

    internal object TestAbacQueryFilter : AbacQueryFilter() {
        override fun getPrincipalTags(
            contextView: ContextView,
            context: me.ahoo.wow.query.filter.QueryContext<*, *>
        ): Mono<AbacTags> = mapOf("role" to listOf("*")).toMono()
    }

    internal object TestStateDynamicDocumentMasker : StateDynamicDocumentMasker {
        override val namedAggregate: NamedAggregate = MOCK_AGGREGATE_METADATA

        override fun mask(dynamicDocument: DynamicDocument): DynamicDocument {
            dynamicDocument.getNestedDocument("state").remove(SECRET)
            return dynamicDocument
        }
    }

    private companion object {
        const val RAW_SERVICE_NAME = "raw"
        const val SECRET = "secret"
        const val RAW_SECRET = "raw-secret"
    }
}

@Suppress("UtilityClassWithPublicConstructor")
class ExistsBeanName {
    companion object {
        const val SNAPSHOT_QUERY_SERVICE = "example.order.SnapshotQueryService"
        const val EVENT_STREAM_QUERY_SERVICE = "example.order.EventStreamQueryService"
    }
}
