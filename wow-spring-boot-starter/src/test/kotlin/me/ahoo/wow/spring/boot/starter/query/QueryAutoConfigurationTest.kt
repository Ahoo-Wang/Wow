package me.ahoo.wow.spring.boot.starter.query

import me.ahoo.wow.query.NoOpSnapshotQueryServiceFactory
import me.ahoo.wow.query.SnapshotQueryServiceFactory
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
            .withBean(SnapshotQueryServiceFactory::class.java, { NoOpSnapshotQueryServiceFactory })
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasBean(ExistsBeanName.SNAPSHOT_QUERY_SERVICE)
            }
    }

    @Test
    fun contextLoadsWhensExists() {
        contextRunner
            .enableWow()
            .withUserConfiguration(QueryAutoConfiguration::class.java)
            .withBean(SnapshotQueryServiceFactory::class.java, { NoOpSnapshotQueryServiceFactory })
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
