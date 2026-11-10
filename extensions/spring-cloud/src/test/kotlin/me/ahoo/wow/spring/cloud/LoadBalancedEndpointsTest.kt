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

package me.ahoo.wow.spring.cloud

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Misuse
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.client.HttpCommandCaller
import me.ahoo.wow.client.Port
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Creation
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.spi.call.OutgoingCall
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.cloud.client.DefaultServiceInstance
import org.springframework.cloud.client.ServiceInstance
import org.springframework.cloud.client.loadbalancer.DefaultResponse
import org.springframework.cloud.client.loadbalancer.EmptyResponse
import org.springframework.cloud.client.loadbalancer.Request
import org.springframework.cloud.client.loadbalancer.Response
import org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer
import reactor.core.publisher.Mono
import java.net.URI
import kotlin.time.Duration.Companion.seconds

/** A WowClient's endpoints through Spring Cloud LoadBalancer (decision D396): `lb://<service>`, an instance per call. */
class LoadBalancedEndpointsTest {
    private val chosen = ArrayDeque<ServiceInstance>()
    private val asked = mutableListOf<String>()

    private val loadBalancers = object : ReactiveLoadBalancer.Factory<ServiceInstance> {
        override fun getInstance(serviceId: String): ReactiveLoadBalancer<ServiceInstance> {
            asked += serviceId
            return ReactiveLoadBalancer { _: Request<*> ->
                val next = chosen.removeFirstOrNull()
                Mono.just<Response<ServiceInstance>>(next?.let(::DefaultResponse) ?: EmptyResponse())
            }
        }

        override fun <X : Any?> getInstances(name: String, type: Class<X>): Map<String, X> = emptyMap()

        override fun <X : Any?> getInstance(name: String, clazz: Class<*>, vararg generics: Class<*>): X? = null
    }

    private val endpoints = LoadBalancedEndpoints.of(loadBalancers, mapOf("bank" to URI.create("lb://bank-service")))

    @Test
    fun `a context's ports should be those of the instance the load balancer chooses for each call`(): Unit =
        runBlocking {
            chosen += node("bank-1", "10.0.0.5:9090")
            chosen += node("bank-2", "10.0.0.6:9090")
            endpoints.resolve("bank", Port.INTERNAL).assert().isEqualTo(URI.create("http://10.0.0.5:9090"))
            endpoints.resolve("bank", Port.PUBLIC).assert().isEqualTo(URI.create("http://10.0.1.2:8080"))
            asked.assert().containsExactly("bank-service", "bank-service")
            endpoints.resolveAll("bank", Port.INTERNAL).assert().isEmpty()
        }

    @Test
    fun `a context not listed, no instance to choose or one without an internal port should have no endpoint`(): Unit =
        runBlocking {
            endpoints.resolve("teller", Port.INTERNAL).assert().isNull()
            asked.assert().isEmpty()
            endpoints.resolve("bank", Port.INTERNAL).assert().isNull()
            chosen += DefaultServiceInstance("bank-3", "bank-service", "10.0.1.3", 8080, false)
            endpoints.resolve("bank", Port.INTERNAL).assert().isNull()
            chosen += DefaultServiceInstance("bank-3", "bank-service", "10.0.1.3", 8080, false)
            endpoints.resolve("bank", Port.PUBLIC).assert().isEqualTo(URI.create("http://10.0.1.3:8080"))
        }

    @Test
    fun `a listed context should be reached without choosing an instance`(): Unit = runBlocking {
        endpoints.reaches("bank", Port.INTERNAL).assert().isTrue()
        endpoints.reaches("bank", Port.PUBLIC).assert().isTrue()
        endpoints.reaches("teller", Port.INTERNAL).assert().isFalse()
        asked.assert().isEmpty()
    }

    /** What fails a call of `bank.account.Open` through [endpoints], as a saga makes it. */
    private fun failureOf(endpoints: LoadBalancedEndpoints = this.endpoints): Throwable? = runCatching {
        val account = AggregateType("bank", "account")
        val contract = Manifest(
            contexts = listOf(
                ContextManifest(
                    "bank",
                    aggregates = listOf(
                        AggregateManifest(
                            "account",
                            state = StateManifest(Manifest.schemaRef("bank.AccountState")),
                            commands = listOf(
                                CommandManifest(
                                    "Open",
                                    schema = Manifest.schemaRef("bank.Open"),
                                    creation = Creation.ALLOWED,
                                    routes = CommandRoutes.of("bank", "account", "Open", Creation.ALLOWED),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val caller = HttpCommandCaller(
            endpoints,
            "teller-app",
            HttpCommandCaller.client(),
            1.seconds,
            contracts = listOf(contract),
        )
        val command = Command(
            "c-1",
            "r-1",
            account.ref("t", "a-1"),
            emptyMap<String, Any>(),
            causation = Causation("root", "root"),
        )
        runBlocking { caller.call(OutgoingCall("bank.account.Open", command)) }
    }.exceptionOrNull()

    @Test
    fun `a listed service with no instance to call should be an outage, a context not listed the code's mistake`() {
        // No instance to choose.
        val none = failureOf() as WowProblemException
        none.code.assert().isEqualTo(FrameworkErrors.PEER_UNAVAILABLE)
        none.message.assert().contains("The lookup of bank lists no node on its internal port now.")
        // An instance registered without its internal port: an outage too, until the peer's nodes register one.
        chosen += DefaultServiceInstance("bank-3", "bank-service", "10.0.1.3", 8080, false)
        (failureOf() as WowProblemException).code.assert().isEqualTo(FrameworkErrors.PEER_UNAVAILABLE)
        // A context the endpoints do not list has no URL at all: the code's mistake, the cause of its failure.
        val unlisted = LoadBalancedEndpoints.of(loadBalancers, emptyMap())
        failureOf(unlisted)!!.cause.assert().isInstanceOf(Misuse.State::class.java)
    }

    @Test
    fun `a service should be named lb, its name and nothing else`() {
        LoadBalancedEndpoints.of(loadBalancers, mapOf("bank" to URI.create("lb://bank_service")))
        listOf("http://bank-service", "lb://bank-service/routes", "lb:bank-service").forEach { uri ->
            assertThrows<IllegalArgumentException> {
                LoadBalancedEndpoints.of(loadBalancers, mapOf("bank" to URI.create(uri)))
            }.message.assert().contains("lb://<service>")
        }
    }

    private fun node(id: String, address: String): ServiceInstance =
        DefaultServiceInstance(
            id,
            "bank-service",
            "10.0.1.2",
            8080,
            false,
            WowInstanceMetadata.of("bank-service", address)
        )
}
