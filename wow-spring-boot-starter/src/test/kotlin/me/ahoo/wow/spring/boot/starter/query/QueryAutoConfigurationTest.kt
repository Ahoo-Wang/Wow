package me.ahoo.wow.spring.boot.starter.query

import io.mockk.every
import io.mockk.spyk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.exception.WowException
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.event.NoOpEventStreamQueryServiceFactory
import me.ahoo.wow.query.event.filter.EventStreamQueryHandler
import me.ahoo.wow.query.mask.EventStreamDynamicDocumentMasker
import me.ahoo.wow.query.mask.StateDynamicDocumentMasker
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryServiceFactory
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
                    .hasBean("noOpSnapshotQueryServiceFactory")
                    .hasBean("noOpEventStreamQueryServiceFactory")
                    .hasSingleBean(MaskingSnapshotQueryFilter::class.java)
                    .hasSingleBean(TailSnapshotQueryFilter::class.java)
                    .hasBean("snapshotQueryFilterChain")
                    .hasBean("eventStreamQueryFilterChain")
                    .hasBean("snapshotQueryErrorHandler")
                    .hasBean("eventStreamQueryErrorHandler")
                    .hasSingleBean(SnapshotQueryHandler::class.java)
                    .hasSingleBean(EventStreamQueryHandler::class.java)

                context.getBean(
                    ExistsBeanName.SNAPSHOT_QUERY_SERVICE,
                    SnapshotQueryService::class.java,
                ).count(Condition.ALL)
                    .test()
                    .expectErrorSatisfies(::assertUnavailable)
                    .verify()
                context.getBean(
                    ExistsBeanName.EVENT_STREAM_QUERY_SERVICE,
                    EventStreamQueryService::class.java,
                ).count(Condition.ALL)
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
                ).count(Condition.ALL)
                    .test()
                    .expectNext(0L)
                    .verifyComplete()
                context.getBean(
                    ExistsBeanName.EVENT_STREAM_QUERY_SERVICE,
                    EventStreamQueryService::class.java,
                ).count(Condition.ALL)
                    .test()
                    .expectNext(0L)
                    .verifyComplete()
            }
    }

    private fun assertUnavailable(error: Throwable) {
        error.assert().isInstanceOf(WowException::class.java)
        (error as WowException).errorCode.assert().isEqualTo(ErrorCodes.INTERNAL_SERVER_ERROR)
        error.message.assert().contains("No query backend is configured")
    }
}

@Suppress("UtilityClassWithPublicConstructor")
class ExistsBeanName {
    companion object {
        const val SNAPSHOT_QUERY_SERVICE = "example.order.SnapshotQueryService"
        const val EVENT_STREAM_QUERY_SERVICE = "example.order.EventStreamQueryService"
    }
}
