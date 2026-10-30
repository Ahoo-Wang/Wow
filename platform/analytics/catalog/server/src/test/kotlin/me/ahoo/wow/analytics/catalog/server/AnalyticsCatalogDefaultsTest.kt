package me.ahoo.wow.analytics.catalog.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseCatalog
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.cosec.CoSecCallerResolver
import me.ahoo.wow.http.caller.CallerResolver
import org.junit.jupiter.api.Test

/** The analytics catalog as it starts without a warehouse: in development, and behind the gateway. */
class AnalyticsCatalogDefaultsTest {
    @Test
    fun `in development a request without a user should be answered 401, the probes and the console's assets not`() {
        startCatalog("wow.analytics.catalog.watch.enabled" to false).use { node ->
            // No ClickHouse client: the catalog has no table.
            node.context.getBean(WarehouseCatalog::class.java).assert().isSameAs(WarehouseCatalog.NONE)
            val nobody = Who(emptyMap())
            // A command, a query and a route of the service's own.
            node.post("/analytics-catalog/source/s-1/draft-source", "{}", nobody)
                .assertProblem(UNAUTHORIZED, "wow.auth.unauthenticated")
            node.sources(nobody).assertProblem(UNAUTHORIZED, "wow.auth.unauthenticated")
            node.get("/analytics-catalog/datasets/shop.orders/reference-actions", nobody)
                .assertProblem(UNAUTHORIZED, "wow.auth.unauthenticated")
            node.sources(Who.ADMIN).assertOk()
            node.get("/actuator/health/liveness", nobody).status.assert().isEqualTo(OK)
            node.get("/actuator/health/readiness", nobody).status.assert().isNotEqualTo(UNAUTHORIZED)
            node.get("/index.html", nobody).status.assert().isNotEqualTo(UNAUTHORIZED)
        }
    }

    @Test
    @OptIn(ExperimentalWowApi::class) // wow-cosec is experimental in 10.0
    fun `behind the gateway no request should be served on its headers' word`() {
        startCatalog("wow.analytics.catalog.watch.enabled" to false, profiles = listOf("cosec")).use { node ->
            node.context.getBean(CallerResolver::class.java).assert().isInstanceOf(CoSecCallerResolver::class.java)
            node.sources(Who.ADMIN).assertProblem(UNAUTHORIZED, "wow.auth.unauthenticated")
        }
    }

    private companion object {
        const val OK = 200
        const val UNAUTHORIZED = 401
    }
}
