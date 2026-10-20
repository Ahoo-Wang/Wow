/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.analytics.catalog.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseCatalog
import me.ahoo.wow.cosec.CoSecCallerResolver
import me.ahoo.wow.http.caller.CallerResolver
import org.junit.jupiter.api.Test

/** The analytics catalog as a deployment starts it without the header switch nor a warehouse. */
class AnalyticsCatalogDefaultsTest {
    @Test
    fun `without the header switch no request should be served on its headers' word`() {
        startCatalog(
            AnalyticsCatalogConfiguration.TRUST_IDENTITY_HEADERS to false,
            "wow.analytics.catalog.watch" to false,
        ).use { other ->
            other.context.getBean(CallerResolver::class.java).assert().isInstanceOf(CoSecCallerResolver::class.java)
            // No ClickHouse client: the catalog has no table.
            other.context.getBean(WarehouseCatalog::class.java).assert().isSameAs(WarehouseCatalog.NONE)
            other.sources(Who.ADMIN).assertProblem(UNAUTHORIZED, "wow.auth.unauthenticated")
        }
    }

    private companion object {
        const val UNAUTHORIZED = 401
    }
}
