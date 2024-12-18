package me.ahoo.wow.spring.boot.starter.query

import me.ahoo.wow.query.event.filter.EventStreamQueryHandler
import me.ahoo.wow.query.snapshot.filter.MaskingSnapshotQueryFilter
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler
import me.ahoo.wow.query.snapshot.filter.TailSnapshotQueryFilter
import me.ahoo.wow.spring.boot.starter.enableWow
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class QueryAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .enableWow()
            .withUserConfiguration(QueryAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasBean(ExistsBeanName.SNAPSHOT_QUERY_SERVICE)
                    .hasSingleBean(MaskingSnapshotQueryFilter::class.java)
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
    fun contextLoadsWhensExists() {
        contextRunner
            .enableWow()
            .withUserConfiguration(QueryAutoConfiguration::class.java)
            .withBean(ExistsBeanName.SNAPSHOT_QUERY_SERVICE, ExistsBeanName::class.java)
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasBean("example.order.SnapshotQueryService")
                    .hasSingleBean(ExistsBeanName::class.java)
            }
    }
}

@Suppress("UtilityClassWithPublicConstructor")
class ExistsBeanName {
    companion object {
        const val SNAPSHOT_QUERY_SERVICE = "example.order.SnapshotQueryService"
    }
}
