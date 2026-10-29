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

import me.ahoo.wow.api.client.CommandRequest
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.model.Filters
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.binding.SubscriptionBindings
import me.ahoo.wow.runtime.log.HandoffLog
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.util.concurrent.CopyOnWriteArrayList

/** The reads a host's MongoDB client sends: each `find`, by collection and filter. */
class MongoReads : CommandListener {
    val finds: MutableList<String> = CopyOnWriteArrayList()

    override fun commandStarted(event: CommandStartedEvent) {
        if (event.commandName == "find") {
            finds += "${event.command.getString("find").value} ${event.command.getDocument("filter").toJson()}"
        }
    }

    /** The snapshots loaded: every `find` on a snapshot collection. */
    val snapshotLoads: List<String> get() = finds.filter { it.startsWith("wow_snapshot__") }

    /** The streams of an aggregate read by version, as a state is rebuilt from the log. */
    val streamReads: List<String> get() = finds.filter { "\"version\"" in it && "\$gte" in it }

    fun reset() = finds.clear()
}

/**
 * A transfer saga on the bank's accounts, as the E3 benchmark's: a deposit of [TRANSFER] into an account whose owner
 * names a target (`to:<id>`) deposits it on the target; every commit it handles is recorded with the state it read.
 */
@Configuration(proxyBeanMethods = false)
class TransferSagaConfiguration {
    @Bean
    fun transfers(): Transfers = Transfers()

    @Bean(destroyMethod = "close")
    fun mongoClient(environment: Environment, reads: MongoReads): MongoClient = MongoClients.create(
        MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(environment.getRequiredProperty("test.mongo.url")))
            .addCommandListener(reads)
            .build(),
    )

    @Bean
    fun mongoReads(): MongoReads = MongoReads()

    @Bean
    fun transferSaga(transfers: Transfers): SubscriptionFactory = SubscriptionBindings(
        mapOf(
            BANK to listOf(
                ProcessorBinding.saga("TransferSaga", "onDeposited", Account::class.java) { event, context ->
                    transfers.handled += "${event.ref.id}@${event.version}:${event.state.balance}"
                    val target = event.state.owner.removePrefix(TARGET).takeIf { event.state.owner.startsWith(TARGET) }
                        ?: return@saga emptyList()
                    event.events.filterIsInstance<Deposited>().filter { it.amount == TRANSFER }.map {
                        CommandRequest.to(event.ref.type.ref(event.ref.tenantId, target), Deposit(it.amount))
                    }
                },
            ),
        ),
    )

    class Transfers {
        val handled: MutableList<String> = CopyOnWriteArrayList()
    }

    companion object {
        const val TARGET = "to:"
        const val TRANSFER = 7L
        const val SAGA = "TransferSaga.onDeposited"
    }
}

/**
 * Decision D261 (B.G-5) on MongoDB through the host: the saga chain of the E3 benchmark, a deposit on one account
 * passed on by a saga to another, on one node. Each account's earlier shared states are gone (their maximum age passed),
 * as between two transfers of the benchmark; the saga and the snapshot subscription still read the state of the
 * node's own commits without loading a snapshot or reading the log, since the executor takes the view before the
 * append that the log hands over (decision D249).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoSagaStateHostTest {
    private val mongo = WowContainers.newMongo().also { it.start() }
    private val host = startHost(
        "wow.mongo.database" to "saga_state_test",
        "wow.log.partitions" to 4,
        "wow.shared-states.max-age" to "${MAX_AGE_MILLIS}ms",
        "test.mongo.url" to mongo.replicaSetUrl,
        sources = listOf(HostTestApp::class.java, TransferSagaConfiguration::class.java),
    )
    private val plain: MongoClient = MongoClients.create(mongo.replicaSetUrl)
    private val reads = host.context.getBean(MongoReads::class.java)
    private val transfers = host.context.getBean(TransferSagaConfiguration.Transfers::class.java)
    private val account = AggregateType(BANK, "account")
    private val caller = Caller(Caller.DEFAULT_TENANT, Scope(ownerId = "dave"))

    @AfterAll
    fun close() {
        plain.close()
        host.close()
        mongo.stop()
    }

    private fun send(command: String, body: String, id: String, wait: WaitPlan): List<CommandResult> = runBlocking {
        host.node.gateway.send(
            ServerCommandRequest(
                context = BANK,
                aggregate = "account",
                command = command,
                body = CommandBody.of(body),
                caller = caller,
                aggregateId = id,
                wait = wait,
            ),
        ).toList()
    }

    /** One transfer: [TRANSFER] deposited on [source], answered once the saga's deposit committed on its target. */
    private fun transfer(source: String, target: String) {
        val last = send("Deposit", """{"amount":${TransferSagaConfiguration.TRANSFER}}""", source, CHAIN).last()
        last.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        last.hop.stage.assert().isEqualTo(Stage.COMMITTED)
        last.ref.id.assert().isEqualTo(target)
    }

    /** The version of [id]'s snapshot, read by a client the test does not count. */
    private fun snapshotVersion(id: String): Int = plain.getDatabase("saga_state_test")
        .getCollection("wow_snapshot__bank__account").find(Filters.eq("aggregate_id", id)).first()
        ?.getInteger("version") ?: 0

    /** Waits until the saga handled [id] at [version] and its snapshot holds it. */
    private fun settled(id: String, version: Int) {
        val deadline = System.nanoTime() + SETTLE_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            if (transfers.handled.any { it.startsWith("$id@$version:") } && snapshotVersion(id) >= version) {
                return
            }
            Thread.sleep(POLL_MILLIS)
        }
        error("$id@$version was not handled and written in time: ${transfers.handled}")
    }

    @Test
    fun `a saga chain on MongoDB should read no snapshot and no stream for the state of the node's own commits`() {
        val pairs = (0 until PAIRS).map { "target-$it" to "source-$it" }
        pairs.forEach { (target, source) ->
            send("OpenAccount", """{"owner":"saga-$target"}""", target, WaitPlan.SNAPSHOT)
                .last().status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            send("OpenAccount", """{"owner":"${TransferSagaConfiguration.TARGET}$target"}""", source, WaitPlan.SNAPSHOT)
                .last().status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        }
        // Warm-up: every partition's tails are open and every account is in the kernel's cache, at version 2.
        pairs.forEach { (target, source) -> transfer(source, target) }
        pairs.forEach { (target, source) ->
            settled(source, 2)
            settled(target, 2)
        }
        val handoffs = host.node.contexts.getValue(BANK).logGroups.map { it.log }.filterIsInstance<HandoffLog>()
        handoffs.assert().isNotEmpty()
        val storeReads = handoffs.sumOf { it.storeReads }
        // Every view of the accounts ages out, as between two transfers of the benchmark.
        Thread.sleep(MAX_AGE_MILLIS * 2)
        reads.reset()

        pairs.forEach { (target, source) -> transfer(source, target) }
        pairs.forEach { (target, source) ->
            settled(source, 3)
            settled(target, 3)
        }

        reads.snapshotLoads.assert().isEmpty()
        reads.streamReads.assert().isEmpty()
        handoffs.sumOf { it.storeReads }.assert().isEqualTo(storeReads)
        // The state read is the committed one: two transfers of 7 on each side.
        pairs.forEach { (target, source) ->
            "$source@3:14".assert().isIn(transfers.handled)
            "$target@3:14".assert().isIn(transfers.handled)
        }
    }

    private companion object {
        const val PAIRS = 4
        const val MAX_AGE_MILLIS = 2_000L
        const val SETTLE_TIMEOUT_NANOS = 20_000_000_000L
        const val POLL_MILLIS = 20L
        val CHAIN: WaitPlan =
            WaitPlan.of(Hop.committed(), Hop.handled(TransferSagaConfiguration.SAGA), Hop.committed())
    }
}
