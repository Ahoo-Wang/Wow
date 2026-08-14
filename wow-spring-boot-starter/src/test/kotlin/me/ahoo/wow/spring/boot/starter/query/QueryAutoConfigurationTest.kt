package me.ahoo.wow.spring.boot.starter.query

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.event.filter.EventStreamQueryHandler
import me.ahoo.wow.query.event.filter.MaskingEventStreamQueryFilter
import me.ahoo.wow.query.mask.EventStreamDynamicDocumentMasker
import me.ahoo.wow.query.mask.EventStreamMaskerRegistry
import me.ahoo.wow.query.mask.MaskingResultPolicy
import me.ahoo.wow.query.mask.StateDataMaskerRegistry
import me.ahoo.wow.query.mask.StateDynamicDocumentMasker
import me.ahoo.wow.query.snapshot.filter.MaskingSnapshotQueryFilter
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler
import me.ahoo.wow.query.snapshot.filter.TailSnapshotQueryFilter
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class QueryAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withBean(QueryGateway::class.java, { mockk<QueryGateway>() })

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
                    .hasSingleBean(MaskingResultPolicy::class.java)
                    .doesNotHaveBean(MaskingSnapshotQueryFilter::class.java)
                    .doesNotHaveBean(MaskingEventStreamQueryFilter::class.java)
                    .hasSingleBean(TailSnapshotQueryFilter::class.java)
                    .hasBean("snapshotQueryFilterChain")
                    .hasBean("eventStreamQueryFilterChain")
                    .hasBean("snapshotQueryErrorHandler")
                    .hasBean("eventStreamQueryErrorHandler")
                    .hasSingleBean(SnapshotQueryHandler::class.java)
                    .hasSingleBean(EventStreamQueryHandler::class.java)
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
    fun `retained legacy masker registration methods are deprecated`() {
        val methods = listOf(
            QueryAutoConfiguration::class.java.getDeclaredMethod("stateDataMaskerRegistry", List::class.java),
            QueryAutoConfiguration::class.java.getDeclaredMethod("eventStreamMaskerRegistry", List::class.java),
            QueryAutoConfiguration::class.java.getDeclaredMethod(
                "maskingSnapshotQueryFilter",
                StateDataMaskerRegistry::class.java
            ),
            QueryAutoConfiguration::class.java.getDeclaredMethod(
                "maskingEventStreamQueryFilter",
                EventStreamMaskerRegistry::class.java
            )
        )

        methods.forEach { method ->
            method.isAnnotationPresent(kotlin.Deprecated::class.java).assert().isTrue()
        }
    }
}

@Suppress("UtilityClassWithPublicConstructor")
class ExistsBeanName {
    companion object {
        const val SNAPSHOT_QUERY_SERVICE = "example.order.SnapshotQueryService"
    }
}
