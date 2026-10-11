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

package me.ahoo.wow.analytics.server

import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.cosec.token.TokenConverter
import me.ahoo.test.asserts.assert
import me.ahoo.wow.cosec.abac.CoSecAbacQueryPolicy
import me.ahoo.wow.query.snapshot.filter.AbacQueryPolicy
import me.ahoo.wow.webflux.route.dataset.DatasetQueryRequestScope
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration

/**
 * The server boots with its own configuration, its middleware (MongoDB, Kafka, Redis, ClickHouse) swapped for
 * in-memory stores and buses and a fixed machine id, and guards its routes: the health probe is open, every route
 * needs a verified token, and the registry's commands need the admin role.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "wow.eventsourcing.store.storage=in_memory",
        "wow.eventsourcing.snapshot.storage=in_memory",
        "wow.mongo.enabled=false",
        "wow.kafka.enabled=false",
        "wow.command.bus.type=in_memory",
        "wow.event.bus.type=in_memory",
        "wow.eventsourcing.state.bus.type=in_memory",
        "wow.query.clickhouse.enabled=false",
        "cosid.machine.distributor.type=manual",
        "cosid.machine.distributor.manual.machine-id=1",
        "cosec.jwt.secret=analytics-boot-test-secret-of-sufficient-length",
    ],
)
class AnalyticsServerBootTest {
    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Autowired
    private lateinit var tokenConverter: TokenConverter

    private val client: WebTestClient by lazy {
        val port = applicationContext.environment.getRequiredProperty("local.server.port")
        WebTestClient.bindToServer().baseUrl("http://localhost:$port").responseTimeout(Duration.ofSeconds(30)).build()
    }

    private fun token(vararg roles: String): String {
        val principal: CoSecPrincipal = SimpleTenantPrincipal(
            SimplePrincipal("analyst", roles = roles.toSet(), attributes = mapOf("abac.region" to "east")),
            SimpleTenant("t1"),
        )
        return tokenConverter.toToken(principal).accessToken
    }

    private fun registryCommand(token: String?): WebTestClient.ResponseSpec =
        client.post().uri("/dataset-registry/dataset/analytics.orders/draft")
            .contentType(MediaType.APPLICATION_JSON)
            .apply { token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") } }
            .bodyValue("""{"table":"orders"}""")
            .exchange()

    @Test
    fun `wires CoSec's principal into ABAC and the dataset tenant`() {
        applicationContext.getBean(AbacQueryPolicy::class.java).assert().isInstanceOf(CoSecAbacQueryPolicy::class.java)
        applicationContext.getBean(DatasetQueryRequestScope::class.java)
            .assert().isInstanceOf(CoSecDatasetQueryRequestScope::class.java)
    }

    @Test
    fun `the health probe is open and every route needs a verified token`() {
        client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk
        client.get().uri("/dataset").exchange().expectStatus().isUnauthorized
        client.get().uri("/dataset").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token").exchange()
            .expectStatus().isUnauthorized
        // Past the guard: without ClickHouse no dataset route is registered here, so the route itself is not found.
        client.get().uri("/dataset").header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}").exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `only the admin role changes the dataset registry`() {
        registryCommand(null).expectStatus().isUnauthorized
        registryCommand(token()).expectStatus().isForbidden
        val admin = registryCommand(token(AnalyticsProperties.DEFAULT_ADMIN_ROLE)).returnResult(String::class.java)
        admin.status.value().assert().isNotIn(401, 403)
    }
}
