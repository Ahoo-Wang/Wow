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

package me.ahoo.wow.spring.boot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.schedule.Misfire
import me.ahoo.wow.http.WowHeaders
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.command.JakartaCommandValidator
import me.ahoo.wow.http.internal.HttpClusterChannel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.FrameworkCodes
import me.ahoo.wow.runtime.cluster.ClusterChannel
import me.ahoo.wow.runtime.node.Routing
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.schedule.JobOptions
import me.ahoo.wow.runtime.state.KernelStateReads
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.runtime.wait.UnknownContexts
import me.ahoo.wow.spi.id.AggregateIdGenerator
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.availability.ReadinessState
import org.springframework.context.SmartLifecycle
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One host on the memory backend serving the test module (roadmap 5b.3): what it assembles, the routes it serves on
 * the public port, and the properties it binds.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HostTest {
    private val host = startHost(
        "wow.log.partitions" to 8,
        "wow.log.append-queue.max-batch" to 16,
        "wow.executor.lane-capacity" to 32,
        "wow.executor.max-in-flight" to 512,
        "wow.state-cache.capacity" to 0,
        "wow.wait.max-hops" to 3,
        "wow.wait.unknown-contexts" to "reject",
        "wow.subscription.max-in-flight" to 64,
        "wow.subscription.retry.max-attempts" to 2,
        "wow.timers.poll-interval" to "250ms",
        "wow.timers.lookahead" to "10s",
        "wow.timers.max-attempts" to 4,
        "wow.timers.expires-after" to "1h",
        "wow.timers.max-early-wait" to "2s",
        "wow.http.errors.[bank.account.frozen].status" to 409,
        "wow.http.request-facts" to "X-Device",
        "spring.lifecycle.timeout-per-shutdown-phase" to "1s",
    )

    @AfterAll
    fun close() {
        host.close()
    }

    @Test
    fun `the node should be assembled from the binding index and run`() {
        val node = host.node
        node.service.assert().isEqualTo("bank-service")
        node.address.assert().isEqualTo("127.0.0.1:${host.internalPort}")
        node.isReady.assert().isTrue()
        node.contexts.keys.assert().containsExactly(BANK)
        node.manifest.service!!.name.assert().isEqualTo("bank-service")
        host.context.getBean(Manifest::class.java).assert().isSameAs(node.manifest)
        host.context.getBean(WowModels::class.java).providers.single().assert().isInstanceOf(BankModels::class.java)
        host.context.getBean(HostBackends::class.java).kind.assert().isEqualTo(BackendKind.MEMORY)
        host.context.getBean(Dependencies::class.java).assert().isInstanceOf(BeanFactoryDependencies::class.java)
        host.context.getBean(ClusterChannel::class.java).assert().isInstanceOf(HttpClusterChannel::class.java)
        host.context.getBean(SignalSender::class.java).assert().isNotNull()
        host.context.getBean(CallerResolver::class.java).assert().isSameAs(CallerResolver.ANONYMOUS)
        host.context.getBean(KernelStateReads::class.java).assert().isNotNull()
        host.context.getBean(GlobalIdGenerator::class.java).assert().isSameAs(GlobalIdGenerator.UUID_V7)
        host.context.getBean(WowAutoConfiguration.WOW_AGGREGATE_ID_GENERATOR, AggregateIdGenerator::class.java)
            .assert().isNotNull()
        node.gateway.ids.global.assert().isSameAs(GlobalIdGenerator.UUID_V7)
        host.internalPortOpen().assert().isTrue()
    }

    @Test
    fun `the properties should configure the node`() {
        val options = host.node.options
        options.routing.assert().isEqualTo(Routing.LOCAL)
        options.cluster.drainTimeout.assert().isEqualTo(1.seconds)
        options.cluster.reconcileInterval.assert().isEqualTo(200.milliseconds)
        options.maxWaitTimeout.assert().isEqualTo(15.seconds)
        options.maxWaitHops.assert().isEqualTo(3)
        options.unknownWaitContexts.assert().isEqualTo(UnknownContexts.REJECT)
        options.appendQueue.enabled.assert().isTrue()
        options.appendQueue.maxBatch.assert().isEqualTo(16)
        options.laneCapacity.assert().isEqualTo(32)
        options.maxInFlight.assert().isEqualTo(512)
        options.stateCacheCapacity.assert().isEqualTo(0)
        options.subscription.maxInFlight.assert().isEqualTo(64)
        options.subscription.retry.maxAttempts.assert().isEqualTo(2)
        options.subscription.checkpointInterval.assert().isEqualTo(100.milliseconds)
        options.timers.pollInterval.assert().isEqualTo(250.milliseconds)
        options.timers.lookahead.assert().isEqualTo(10.seconds)
        options.timers.retry.maxAttempts.assert().isEqualTo(4)
        options.timers.expiresAfter.assert().isEqualTo(1.hours)
        options.timers.maxEarlyWait.assert().isEqualTo(2.seconds)
        host.node.context(BANK).timers!!.runner.partitions.size.assert().isEqualTo(8)
        host.node.context(BANK).log.partitions.assert().isEqualTo(8)
    }

    @Test
    fun `a job's schedule properties should override its rule, part by part`() {
        WowProperties.Schedule().options().assert().isEqualTo(JobOptions())
        val schedule = WowProperties.Schedule().apply {
            expression = "0 0 4 * * *"
            zone = "Asia/Shanghai"
            misfire = Misfire.CATCH_UP
            enabled = false
        }
        schedule.options().assert().isEqualTo(JobOptions("0 0 4 * * *", "Asia/Shanghai", Misfire.CATCH_UP, false))
        host.node.options.schedules.assert().isEmpty()
    }

    @Test
    fun `Wow's JSON mapper should be a bean that leaves Spring's mapper alone`() {
        host.context.getBean(WowAutoConfiguration.WOW_JSON_MAPPER).assert().isSameAs(WowJson.mapper)
        host.context.getBean(JsonMapper::class.java).assert().isNotSameAs(WowJson.mapper)
    }

    @Test
    fun `the Jakarta validator should be enabled by the application's Bean Validation provider`() {
        host.context.getBean(JakartaCommandValidator::class.java).assert().isNotNull()
        val id = openAccount()
        val answer = host.post("/bank/account/$id/deposit", """{"amount":-1}""")
        answer.status.assert().isEqualTo(400)
        answer.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.INVALID)
    }

    @Test
    fun `a command over HTTP should wait for the snapshot by default`() {
        val answer = host.post("/bank/account/open-account", """{"owner":"alice"}""")
        answer.status.assert().isEqualTo(200)
        val result = answer.body!!
        result["status"].asString().assert().isEqualTo("SUCCEEDED")
        result["hop"]["stage"].asString().assert().isEqualTo("HANDLED")
        result["hop"]["subscription"].asString().assert().isEqualTo("snapshot")
        val id = result["ref"]["id"].asString()
        UUID.fromString(id).version().assert().isEqualTo(7)
        host.decided.ids.assert().contains(id)
    }

    @Test
    fun `the state of an aggregate should be read from its snapshot`() {
        val id = openAccount()
        host.post("/bank/account/$id/deposit", """{"amount":5}""").status.assert().isEqualTo(200)
        val state = host.get("/bank/account/$id/state")
        state.status.assert().isEqualTo(200)
        state.headers.getFirst(WowHeaders.ETAG).assert().isEqualTo("\"2\"")
        state.body!!["state"]["balance"].asLong().assert().isEqualTo(5)
        state.body["state"]["owner"].asString().assert().isEqualTo("bob")
        host.get("/bank/account/$id/state?version=1").body!!["state"]["balance"].asLong().assert().isEqualTo(0)
    }

    @Test
    fun `the error statuses should follow wow-http-errors`() {
        val id = openAccount()
        val answer = host.post("/bank/account/$id/deposit", """{"amount":$FROZEN_AMOUNT}""")
        answer.status.assert().isEqualTo(409)
        answer.body!!["code"].asString().assert().isEqualTo(FROZEN)
    }

    @Test
    fun `the Manifest should be served at wow-manifest`() {
        val answer = host.get("/wow/manifest")
        answer.status.assert().isEqualTo(200)
        answer.body!!["service"]["name"].asString().assert().isEqualTo("bank-service")
        answer.body["contexts"][0]["name"].asString().assert().isEqualTo(BANK)
    }

    @Test
    fun `the OpenAPI document of the routes should be served at wow-openapi`() {
        val answer = host.get("/wow/openapi")
        answer.status.assert().isEqualTo(200)
        answer.body!!["openapi"].asString().assert().isEqualTo("3.1.0")
        answer.body["info"]["title"].asString().assert().isEqualTo("bank-service")
        answer.body["paths"].propertyNames().assert().anyMatch { it.startsWith("/$BANK/") }
    }

    @Test
    fun `the internal routes should not be served on the public port`() {
        host.post("/wow/internal/signals", "{}").status.assert().isEqualTo(404)
        host.post("/wow/internal/forward", "{}").status.assert().isEqualTo(404)
    }

    @Test
    fun `the lifecycles should run in the phases of the start and stop order`() {
        val webServer = host.context.getBean("webServerStartStop", SmartLifecycle::class.java)
        webServer.phase.assert().isEqualTo(WowLifecycles.PUBLIC_SERVER_PHASE)
        host.context.getBean(ServiceNodeLifecycle::class.java).phase.assert().isEqualTo(WowLifecycles.NODE_PHASE)
        host.context.getBean(InternalServerLifecycle::class.java).phase
            .assert().isEqualTo(WowLifecycles.INTERNAL_SERVER_PHASE)
        host.context.getBean(BackendsLifecycle::class.java).phase.assert().isEqualTo(WowLifecycles.BACKENDS_PHASE)
        (WowLifecycles.BACKENDS_PHASE < WowLifecycles.INTERNAL_SERVER_PHASE).assert().isTrue()
        (WowLifecycles.INTERNAL_SERVER_PHASE < WowLifecycles.NODE_PHASE).assert().isTrue()
        (WowLifecycles.NODE_PHASE < WowLifecycles.PUBLIC_SERVER_PHASE).assert().isTrue()
        host.context.getBean(ServiceNodeLifecycle::class.java).isRunning.assert().isTrue()
        host.context.getBean(InternalServerLifecycle::class.java).isRunning.assert().isTrue()
        host.context.getBean(BackendsLifecycle::class.java).isRunning.assert().isTrue()
    }

    @Test
    fun `the application should be ready once the node runs`() {
        host.context.getBean(ApplicationAvailability::class.java).readinessState
            .assert().isEqualTo(ReadinessState.ACCEPTING_TRAFFIC)
        host.node.state.assert().isEqualTo(ServiceNode.State.RUNNING)
    }

    private fun openAccount(owner: String = "bob"): String {
        val answer = host.post("/bank/account/open-account", """{"owner":"$owner"}""")
        answer.status.assert().isEqualTo(200)
        return answer.body!!["ref"]["id"].asString()
    }
}
