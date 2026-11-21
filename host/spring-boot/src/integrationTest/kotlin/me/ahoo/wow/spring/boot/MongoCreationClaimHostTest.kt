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

    /** The ids the account's configured generator gives next, in order; a random one once none is left. */
    val ids = ConcurrentLinkedQueue<String>()

    fun arm() {
        entered = CountDownLatch(1)
        release = CountDownLatch(1)
        armed.set(true)
    }
}

/** The holding validator of [HeldAdmission]. */
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

    companion object {
        const val TIMEOUT_SECONDS = 20L
    }
}

/** The account's generator of [HeldAdmission], which `wow.aggregates.[bank.account].id-generator` names. */
@Configuration(proxyBeanMethods = false)
class ClaimTestIdsConfiguration {
    @Bean
    fun claimTestIds(): AggregateIdGenerator =
        AggregateIdGenerator { HeldAdmission.ids.poll() ?: "account-${UUID.randomUUID()}" }
}

/**
 * #6518 on the node as production assembles it (`ServiceNode`, its own gateway), on MongoDB: a creation that names
 * no id, retried with its request id while its first attempt is still in flight, commits one account. The first
 * attempt is held at admission once its id is taken; the order is the test's, not timing's: the retry is sent once the
 * first attempt is held, and the first is let go once the retry is answered. Through the public port and through a
 * saga's call, which reach one gateway, `ServiceNode.gateway`.
 *
 * Each host takes the account's ids one way: from a generator the configuration names, claimed for the request
 * ([MongoCreationClaimHostTest]), or from Wow's default generator, aimed at the request's partition with no claim
 * ([MongoCreationAimedHostTest]).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class MongoCreationHostSpec(
    private val partitions: Int,
    properties: List<Pair<String, Any>>,
    sources: List<Class<*>>,
) {
    private val mongo = WowContainers.newMongo().also { it.start() }
    protected val host = startHost(
        "wow.mongo.database" to DATABASE,
        "wow.log.partitions" to partitions,
        "test.mongo.url" to mongo.replicaSetUrl,
        *properties.toTypedArray(),
        sources = listOf(
            HostTestApp::class.java,
            MongoClientConfiguration::class.java,
            HeldAdmissionConfiguration::class.java,
        ) + sources,
    )
    private val database get() = host.context.getBean(MongoClient::class.java).getDatabase(DATABASE)

    @AfterAll
    fun close() {
        host.close()
        mongo.stop()
    }

    /** The tenant the host gives the test's caller: an account opened first says. */
    protected val tenant: String by lazy {
        host.post("/bank/account/open-account", """{"owner":"tenant"}""").body!!["ref"]["tenantId"].asString()
    }

    protected fun partitionOf(id: String) = Partitioner.partitionOf(ACCOUNT.ref(tenant, id), partitions)

    /** What the host's generator gives the first attempt and the retry, when the test chooses it; nothing otherwise. */
    protected abstract fun queueIds(prefix: String): List<String>

    /** The claims a request of this host's account keeps: one when its ids are claimed, none when drawn. */
    protected abstract val claimsPerRequest: Long

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
        val ids = queueIds("port")
        val request = "port-${UUID.randomUUID()}"
        twice {
            host.post(
                "/bank/account/open-account",
                """{"owner":"held-port"}""",
                mapOf(WowHeaders.REQUEST_ID to request, WowHeaders.WAIT to "committed"),
            ).status.assert().isEqualTo(200)
        }
        commits(request).assert().describedAs("ids queued: $ids").isEqualTo(1)
        claims(request).assert().isEqualTo(claimsPerRequest)
    }

    @Test
    fun `a retry of a saga's call while the first attempt is in flight commits one account`() {
        val ids = queueIds("call")
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
        commits(request).assert().describedAs("ids queued: $ids").isEqualTo(1)
        claims(request).assert().isEqualTo(claimsPerRequest)
    }

    protected companion object {
        const val DATABASE = "creation_claim_host"
        val ACCOUNT = AggregateType(BANK, "account")
    }
}

/**
 * [MongoCreationHostSpec] with a generator the configuration names for the account: its ids are claimed. The first
 * attempt and the retry draw ids in two partitions, the ones MongoDB's request key, unique per partition, would not
 * meet; the request's claim keeps them to one account.
 */
class MongoCreationClaimHostTest : MongoCreationHostSpec(
    partitions = 4,
    properties = listOf("wow.aggregates.[bank.account].id-generator" to "claimTestIds"),
    sources = listOf(ClaimTestIdsConfiguration::class.java),
) {
    override val claimsPerRequest = 1L

    override fun queueIds(prefix: String): List<String> {
        val first = "$prefix-0"
        val second = generateSequence(1) { it + 1 }.map { "$prefix-$it" }
            .first { partitionOf(it) != partitionOf(first) }
        HeldAdmission.ids.clear()
        HeldAdmission.ids.addAll(listOf(first, second))
        return listOf(first, second)
    }

    @Test
    fun `the account's configured generator does not aim, every other type's ids do`() {
        val ids = host.node.gateway.ids.aggregates
        ids.nextIn(ACCOUNT, "t", 0, 4).assert().isNull()
        ids.nextIn(AggregateType(BANK, "ledger"), "t", 0, 4).assert().isNotNull()
    }
}

/**
 * [MongoCreationHostSpec] with Wow's default generator: every attempt's id is aimed at the request's partition, with
 * no claim, so the retry and the first attempt meet at the request key. Of 64 partitions, so two ids not aimed would
 * fall apart 63 times in 64.
 */
class MongoCreationAimedHostTest : MongoCreationHostSpec(
    partitions = 64,
    properties = emptyList(),
    sources = emptyList()
) {
    override val claimsPerRequest = 0L

    override fun queueIds(prefix: String): List<String> = emptyList()

    @Test
    fun `every type's ids come from Wow's default generator, which aims`() {
        host.node.gateway.ids.aggregates.nextIn(ACCOUNT, "t", 0, 4).assert().isNotNull()
    }
}
