package me.ahoo.wow.analytics.catalog.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseCatalog
import me.ahoo.wow.cosec.CoSecCallerResolver
import me.ahoo.wow.http.caller.CallerResolver
import org.junit.jupiter.api.Test

/** The analytics catalog as it starts without a warehouse: in development, and behind the gateway. */
class AnalyticsCatalogDefaultsTest {
    @Test
    fun `in development a request without a user should be answered 401`() {
        startCatalog("wow.analytics.catalog.watch.enabled" to false).use { node ->
            // No ClickHouse client: the catalog has no table.
            node.context.getBean(WarehouseCatalog::class.java).assert().isSameAs(WarehouseCatalog.NONE)
            node.sources(Who(emptyMap())).assertProblem(UNAUTHORIZED, "wow.auth.unauthenticated")
            node.sources(Who.ADMIN).assertOk()
        }
    }

    @Test
    fun `behind the gateway no request should be served on its headers' word`() {
        startCatalog("wow.analytics.catalog.watch.enabled" to false, profiles = listOf("cosec")).use { node ->
            node.context.getBean(CallerResolver::class.java).assert().isInstanceOf(CoSecCallerResolver::class.java)
            node.sources(Who.ADMIN).assertProblem(UNAUTHORIZED, "wow.auth.unauthenticated")
        }
    }

    private companion object {
        const val UNAUTHORIZED = 401
    }
}
