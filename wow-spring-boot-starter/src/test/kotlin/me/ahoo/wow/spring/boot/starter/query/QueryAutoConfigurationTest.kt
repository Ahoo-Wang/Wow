package me.ahoo.wow.spring.boot.starter.query

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.query.event.EventStreamQueryBackendProvider
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.event.EventStreamQueryGatewayFactory
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.event.filter.EventStreamQueryHandler
import me.ahoo.wow.query.mask.EventStreamDynamicDocumentMasker
import me.ahoo.wow.query.mask.StateDynamicDocumentMasker
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendProvider
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.query.snapshot.SnapshotQueryGatewayFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.filter.MaskingSnapshotQueryFilter
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler
import me.ahoo.wow.query.snapshot.filter.TailSnapshotQueryFilter
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

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
                    .hasSingleBean(MaskingSnapshotQueryFilter::class.java)
                    .hasSingleBean(TailSnapshotQueryFilter::class.java)
                    .hasBean("snapshotQueryFilterChain")
                    .hasBean("eventStreamQueryFilterChain")
                    .hasBean("snapshotQueryErrorHandler")
                    .hasBean("eventStreamQueryErrorHandler")
                    .hasSingleBean(SnapshotQueryHandler::class.java)
                    .hasSingleBean(EventStreamQueryHandler::class.java)
                    .hasSingleBean(SnapshotQueryBackendProvider::class.java)
                    .hasSingleBean(EventStreamQueryBackendProvider::class.java)
                    .hasSingleBean(SnapshotQueryGatewayFactory::class.java)
                    .hasSingleBean(EventStreamQueryGatewayFactory::class.java)
                context.getBean(
                    ExistsBeanName.SNAPSHOT_QUERY_SERVICE,
                    SnapshotQueryService::class.java,
                ).assert().isInstanceOf(SnapshotQueryGateway::class.java)
                context.getBean(
                    ExistsBeanName.EVENT_STREAM_QUERY_SERVICE,
                    EventStreamQueryService::class.java,
                ).assert().isInstanceOf(EventStreamQueryGateway::class.java)
            }
    }

    @Test
    fun `should bind gateway metadata and tail execution to same snapshot backend`() {
        val backend = mockk<SnapshotQueryService<Any>> {
            every { name } returns "backend"
            every { count(Condition.ALL) } returns Mono.just(1)
        }
        val backendFactory = mockk<SnapshotQueryServiceFactory> {
            every { create<Any>(any()) } returns backend
        }

        contextRunner
            .enableWow()
            .withUserConfiguration(QueryAutoConfiguration::class.java)
            .withBean(SnapshotQueryServiceFactory::class.java, { backendFactory })
            .run { context: AssertableApplicationContext ->
                val gateway = context.getBean(
                    ExistsBeanName.SNAPSHOT_QUERY_SERVICE,
                    SnapshotQueryService::class.java,
                )

                gateway.name.assert().isEqualTo("backend")
                gateway.count(Condition.ALL)
                    .test()
                    .expectNext(1)
                    .verifyComplete()
                val targetAggregate = gateway.namedAggregate.materialize()
                verify(exactly = 1) {
                    backendFactory.create<Any>(match { it.materialize() == targetAggregate })
                }
                verify(exactly = 1) { backend.count(Condition.ALL) }
            }
    }

    @Test
    fun `should bind gateway and tail execution to same event stream backend`() {
        val backend = mockk<EventStreamQueryService> {
            every { count(Condition.ALL) } returns Mono.just(1)
        }
        val backendFactory = mockk<EventStreamQueryServiceFactory> {
            every { create(any()) } returns backend
        }

        contextRunner
            .enableWow()
            .withUserConfiguration(QueryAutoConfiguration::class.java)
            .withBean(EventStreamQueryServiceFactory::class.java, { backendFactory })
            .run { context: AssertableApplicationContext ->
                val gateway = context.getBean(
                    ExistsBeanName.EVENT_STREAM_QUERY_SERVICE,
                    EventStreamQueryService::class.java,
                )

                gateway.count(Condition.ALL)
                    .test()
                    .expectNext(1)
                    .verifyComplete()
                val targetAggregate = gateway.namedAggregate.materialize()
                verify(exactly = 1) {
                    backendFactory.create(match { it.materialize() == targetAggregate })
                }
                verify(exactly = 1) { backend.count(Condition.ALL) }
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
}

@Suppress("UtilityClassWithPublicConstructor")
class ExistsBeanName {
    companion object {
        const val EVENT_STREAM_QUERY_SERVICE = "example.order.EventStreamQueryService"
        const val SNAPSHOT_QUERY_SERVICE = "example.order.SnapshotQueryService"
    }
}
