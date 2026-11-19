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

import com.mongodb.client.MongoClient
import com.mongodb.client.model.Filters
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.CommandValidator
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.spi.id.AggregateIdGenerator
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Holds the first `OpenAccount` of an owner named `held-…` at admission, after its id is drawn (and claimed) and
 * before it is routed, until the test lets it go: an attempt still in flight when its request is retried.
 */
object HeldAdmission {
    val armed = AtomicBoolean()

    @Volatile
    var entered = CountDownLatch(1)

    @Volatile
    var release = CountDownLatch(1)

    /** The ids the account's generator gives next, in order; a random one once none is left. */
    val ids = ConcurrentLinkedQueue<String>()

    fun arm() {
        entered = CountDownLatch(1)
        release = CountDownLatch(1)
        armed.set(true)
    }
}

/** The holding validator and the account's generator of [HeldAdmission]. */
@Configuration(proxyBeanMethods = false)
class HeldAdmissionConfiguration {
    @Bean
    fun heldAdmission(): CommandValidator = CommandValidator { command ->
        val held = (command.body as? OpenAccount)?.owner?.startsWith("held-") == true
        if (held && HeldAdmission.armed.compareAndSet(true, false)) {
            HeldAdmission.entered.countDown()
            HeldAdmission.release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        emptyList()
    }

    @Bean
    fun claimTestIds(): AggregateIdGenerator =
        AggregateIdGenerator { HeldAdmission.ids.poll() ?: "account-${UUID.randomUUID()}" }

    companion object {
        const val TIMEOUT_SECONDS = 20L
    }
}

/**
 * #6518 on the node as production assembles it (`ServiceNode`, its own gateway), on MongoDB: a creation that names
 * no id, retried with its request id while its first attempt is still in flight, commits one account. The first
 * attempt is held at admission once its id is drawn; the retry then draws an id in another partition, the one MongoDB's
 * request key, unique per partition, would not meet. The order is the test's, not timing's: the retry is sent once the
 * first attempt is held, and the first is let go once the retry is answered. Through the public port and through a
 * saga's call, which reach one gateway, `ServiceNode.gateway`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoCreationClaimHostTest {
    private val mongo = WowContainers.newMongo().also { it.start() }
    private val host = startHost(
        "wow.mongo.database" to DATABASE,
        "wow.log.partitions" to PARTITIONS,
        "wow.aggregates.[bank.account].id-generator" to "claimTestIds",
        "test.mongo.url" to mongo.replicaSetUrl,
        sources = listOf(
            HostTestApp::class.java,
            MongoClientConfiguration::class.java,
            HeldAdmissionConfiguration::class.java,
        ),
    )
    private val database get() = host.context.getBean(MongoClient::class.java).getDatabase(DATABASE)

    @AfterAll
    fun close() {
        host.close()
        mongo.stop()
    }

    /** The tenant the host gives the test's caller: an account opened first says. */
    private val tenant: String by lazy {
        host.post("/bank/account/open-account", """{"owner":"tenant"}""").body!!["ref"]["tenantId"].asString()
    }

    private fun partitionOf(id: String) =
        Partitioner.partitionOf(AggregateType(BANK, "account").ref(tenant, id), PARTITIONS)

    /** Two ids of the account in two partitions, queued for the first attempt's draw and the retry's. */
    private fun queueIdsApart(prefix: String): List<String> {
        val first = "$prefix-0"
        val second = generateSequence(1) { it + 1 }.map { "$prefix-$it" }
            .first { partitionOf(it) != partitionOf(first) }
        HeldAdmission.ids.clear()
        HeldAdmission.ids.addAll(listOf(first, second))
        return listOf(first, second)
    }

    /** The streams the request committed, and its claims. */
    private fun commits(request: String): Long =
        database.getCollection("wow_log__bank__account").countDocuments(Filters.eq("request_id", request))

    private fun claims(request: String): Long =
        database.getCollection("wow_claim__bank__account").countDocuments(Filters.eq("request_id", request))

    /** [attempt] twice with one request: the first held at admission, the retry sent then, the first let go after. */
    private fun twice(attempt: () -> Unit) {
        HeldAdmission.arm()
        val first = thread { attempt() }
        HeldAdmission.entered.await(HeldAdmissionConfiguration.TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .assert().isTrue()
        attempt()
        HeldAdmission.release.countDown()
        first.join(TimeUnit.SECONDS.toMillis(HeldAdmissionConfiguration.TIMEOUT_SECONDS))
    }

    @Test
    fun `a retry through the public port while the first attempt is in flight commits one account`() {
        val ids = queueIdsApart("port")
        val request = "port-${UUID.randomUUID()}"
        twice {
            host.post(
                "/bank/account/open-account",
                """{"owner":"held-port"}""",
                mapOf(WowHeaders.REQUEST_ID to request, WowHeaders.WAIT to "committed"),
            ).status.assert().isEqualTo(200)
        }
        commits(request).assert().describedAs("ids drawn: $ids").isEqualTo(1)
        claims(request).assert().isEqualTo(1)
    }

    @Test
    fun `a retry of a saga's call while the first attempt is in flight commits one account`() {
        val ids = queueIdsApart("call")
        val request = "call-${UUID.randomUUID()}"
        val call = CallContext(
            commandId = "call-$request",
            tenantId = tenant,
            scope = Scope("held"),
            causation = Causation("root-1", "checkout/t/held@1"),
            operator = Operator("held", tenant),
        )
        twice {
            val result = runBlocking {
                host.node.gateway.execute(
                    ServerCommandRequest(
                        context = BANK,
                        aggregate = "account",
                        command = "OpenAccount",
                        body = CommandBody.of("""{"owner":"held-call"}"""),
                        caller = Caller(subject = "service:checkout", service = true),
                        aggregateId = null,
                        requestId = request,
                        call = call,
                    ),
                )
            }
            result.error.assert().describedAs(result.toString()).isNull()
        }
        commits(request).assert().describedAs("ids drawn: $ids").isEqualTo(1)
        claims(request).assert().isEqualTo(1)
    }

    private companion object {
        const val DATABASE = "creation_claim_host"
        const val PARTITIONS = 4
    }
}
