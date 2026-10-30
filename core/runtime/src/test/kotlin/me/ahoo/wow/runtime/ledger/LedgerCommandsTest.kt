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

package me.ahoo.wow.runtime.ledger

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.ledger.ApplyParkedRetrySpec
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.ledger.LedgerCommand
import me.ahoo.wow.api.ledger.MarkParkedRecoverable
import me.ahoo.wow.api.ledger.ResumeParked
import me.ahoo.wow.api.ledger.SkipParked
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.CommandWire
import me.ahoo.wow.runtime.cluster.ReplyTo
import me.ahoo.wow.runtime.node.ParkedOps
import me.ahoo.wow.runtime.subscription.Appender
import me.ahoo.wow.runtime.subscription.ParkedRetryOptions
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.subscription.cart
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.LedgerEntry
import me.ahoo.wow.spi.ledger.LedgerRequest
import me.ahoo.wow.spi.ledger.LedgerResolution
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.ledger.Recoverable
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The ledger's system commands on the owning service (decision D141): taken from the ops context's saga alone
 * (decision D226), run, and published.
 */
class LedgerCommandsTest {
    private val log = InMemoryEventLog(partitions = 2)
    private val appender = Appender(log)
    private val scanOnResolve = AtomicBoolean(false)

    /** The ledger, which scans the publisher right after it resolves an entry while [scanOnResolve] is set. */
    private val ledger: FailureLedger = ScanningLedger(InMemoryFailureLedger())

    private inner class ScanningLedger(private val inner: FailureLedger) : FailureLedger by inner {
        override suspend fun resolve(subscription: String, ref: AggregateRef): ParkedAggregate? =
            inner.resolve(subscription, ref).also { if (scanOnResolve.get()) publisher.scan() }
    }

    private val failing = AtomicBoolean(true)
    private val handled = CopyOnWriteArrayList<String>()
    private val subscription = Subscription(NAME, SubscriptionFilter("shop")) { entry ->
        if (failing.get()) error("${entry.ref.id} is broken")
        handled += entry.ref.id
    }
    private val spec = ParkedRetrySpec(maxRetries = 2, initialBackoff = 1.hours, timeout = 1.seconds)
    private val runtime = SubscriptionRuntime(
        "group",
        log,
        InMemoryCheckpointStore(),
        ledger,
        listOf(subscription),
        testOptions(maxAttempts = 1).copyWith(parked = ParkedRetryOptions(spec, scanInterval = 1.hours)),
    )
    private val ops = ParkedOps { listOf(ParkedOps.Scope.of(runtime)) }
    private val sent = CopyOnWriteArrayList<CommandWire>()
    private val publisher = LedgerPublisher("shop-service", LedgerPublishing(), ops, { sent += it })
    private val commands = LedgerCommands(ops, Ledger.OPS_CONTEXT) { publisher }

    private suspend fun parkAll(vararg ids: String, start: Boolean = true) {
        ids.forEach { appender.append(it) }
        runtime.start(setOf(0, 1))
        eventually { ledger.list(NAME).size == ids.size }
        if (!start) runtime.stop()
    }

    private fun wire(command: LedgerCommand, operator: Operator? = ALICE): CommandWire = wire(
        Ledger.nameOf(command.javaClass)!!,
        ClusterCodec.mapper.valueToTree(command),
        command.toRef(),
        operator,
    )

    /** A ledger command as the ops context's saga derives it ([source]): with a causation, its reply to that context. */
    private fun wire(
        type: String,
        body: JsonNode,
        ref: AggregateRef = cart("x1"),
        operator: Operator? = ALICE,
        source: String? = Ledger.OPS_CONTEXT,
        causation: Causation? = CAUSATION,
    ) = CommandWire(
        type,
        "c-1",
        "r-1",
        ref,
        body,
        scope = Scope.NONE,
        causation = causation,
        operator = operator,
        replyTo = source?.let { ReplyTo(it, "ParkedOperationSaga.onParkedChanged", PARKED_ENTRY, 2) },
    )

    private fun wire(command: LedgerCommand, source: String?, causation: Causation? = CAUSATION): CommandWire = wire(
        Ledger.nameOf(command.javaClass)!!,
        ClusterCodec.mapper.valueToTree(command),
        command.toRef(),
        source = source,
        causation = causation,
    )

    private fun lastRecord(): JsonNode = sent.last().record

    /** A skip of [ref]'s entry, naming the parking the ledger holds, as the ops saga sends one. */
    private suspend fun skipOf(ref: AggregateRef): SkipParked =
        SkipParked.of(NAME, ref, ledger.parked(NAME, ref)!!.failedAt)

    /**
     * The flake of LedgerNodeTest and EshopLedgerOpsTest: the reconciliation scan ran after the resume resolved the
     * entry and before the resume published its outcome, so it published the entry as RESOLVED first, and ops kept
     * that. Here the scan runs exactly there, every time.
     */
    @Test
    fun `a scan between an action's resolution and its publication leaves the outcome to the action`(): Unit =
        runBlocking {
            parkAll("x1", "x2")
            publisher.scan()
            failing.set(false)
            scanOnResolve.set(true)
            commands.execute(wire(ResumeParked.of(NAME, X1))).assert().isNull()
            commands.execute(wire(skipOf(cart("x2")))).assert().isNull()
            scanOnResolve.set(false)
            publisher.scan()
            sent.filter { it.type.endsWith(Ledger.RECORD_RESOLVED) }
                .map { it.record["aggregate"]["id"].asString() to it.record["outcome"].asString() }
                .assert().containsExactly("x1" to LedgerResolution.RESUMED, "x2" to LedgerResolution.SKIPPED)
            runtime.stop()
        }

    @Test
    fun `a resume of an entry this node runs handles it again and publishes it resolved`(): Unit = runBlocking {
        parkAll("x1")
        commands.execute(wire(ResumeParked.of(NAME, X1))).assert().isNull()
        sent.last().record["outcome"].assert().isNull()
        ClusterCodec.mapper.treeToValue(lastRecord(), LedgerEntry::class.java).fromVersion.assert().isEqualTo(1)
        failing.set(false)
        commands.execute(wire(ResumeParked.of(NAME, X1))).assert().isNull()
        handled.assert().containsExactly("x1")
        ClusterCodec.mapper.treeToValue(lastRecord(), LedgerResolution::class.java).let {
            it.outcome.assert().isEqualTo(LedgerResolution.RESUMED)
            it.context.assert().isEqualTo("shop")
        }
        commands.execute(wire(ResumeParked.of(NAME, X1))).assert().isNull()
        lastRecord()["outcome"].asString().assert().isEqualTo(LedgerResolution.NOT_PARKED)
        runtime.stop()
    }

    @Test
    fun `a resume or a skip of another parking than the entry's is refused as stale`(): Unit = runBlocking {
        parkAll("x1")
        val failedAt = ledger.parked(NAME, X1)!!.failedAt
        // The parking the operator saw was resolved, and the aggregate parked again since: its time differs.
        commands.execute(wire(SkipParked.of(NAME, X1, failedAt - 1)))!!.code.assert()
            .isEqualTo(FrameworkCodes.LEDGER_STALE)
        commands.execute(wire(ResumeParked.of(NAME, X1, failedAt + 1)))!!.code.assert()
            .isEqualTo(FrameworkCodes.LEDGER_STALE)
        ledger.isParked(NAME, X1).assert().isTrue()
        // The parking it holds is acted on.
        commands.execute(wire(SkipParked.of(NAME, X1, failedAt))).assert().isNull()
        lastRecord()["outcome"].asString().assert().isEqualTo(LedgerResolution.SKIPPED)
        runtime.stop()
    }

    @Test
    fun `a skip resolves the entry, and one of a partition another node runs is left as a request`(): Unit =
        runBlocking {
            parkAll("x1", "x2")
            commands.execute(wire(skipOf(X1))).assert().isNull()
            lastRecord()["outcome"].asString().assert().isEqualTo(LedgerResolution.SKIPPED)
            runtime.stop()
            commands.execute(wire(ResumeParked.of(NAME, cart("x2")))).assert().isNull()
            ledger.parked(NAME, cart("x2"))!!.request.assert().isEqualTo(LedgerRequest.RESUME)
            lastRecord()["request"].asString().assert().isEqualTo("resume")
        }

    @Test
    fun `an entry is marked and given a spec, each published as it now stands`(): Unit = runBlocking {
        parkAll("x1", start = false)
        commands.execute(wire(MarkParkedRecoverable.of(NAME, X1, "unrecoverable"))).assert().isNull()
        lastRecord()["status"].asString().assert().isEqualTo("unrecoverable")
        commands.execute(wire(MarkParkedRecoverable.of(NAME, X1, "maybe")))!!.code.assert()
            .isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
        commands.execute(wire(MarkParkedRecoverable.of(NAME, X1, "recoverable"))).assert().isNull()
        ledger.parked(NAME, cart("x1"))!!.recoverable.assert().isEqualTo(Recoverable.RECOVERABLE)

        commands.execute(wire(ApplyParkedRetrySpec.of(NAME, X1, initialBackoff = "PT5M", timeout = "30s")))
            .assert().isNull()
        ledger.parked(NAME, cart("x1"))!!.retrySpec.assert()
            .isEqualTo(ParkedRetrySpec(maxRetries = 2, initialBackoff = 5.minutes, timeout = 30.seconds))
        lastRecord()["retrySpec"]["applied"].asBoolean().assert().isTrue()
        commands.execute(wire(ApplyParkedRetrySpec.of(NAME, X1, maxRetries = 0))).assert().isNull()
        ledger.parked(NAME, cart("x1"))!!.retrySpec!!.maxRetries.assert().isEqualTo(0)
        commands.execute(wire(ApplyParkedRetrySpec.of(NAME, X1, initialBackoff = "soon")))!!.code.assert()
            .isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
        commands.execute(wire(ApplyParkedRetrySpec.of(NAME, X1, maxRetries = -1)))!!.code.assert()
            .isEqualTo(FrameworkCodes.COMMAND_MALFORMED)

        val absent = cart("absent")
        sent.clear()
        commands.execute(wire(MarkParkedRecoverable.of(NAME, absent, "recoverable"))).assert().isNull()
        commands.execute(wire(ApplyParkedRetrySpec.of(NAME, absent, maxRetries = 1))).assert().isNull()
        sent.map { it.record["outcome"].asString() }.assert().containsExactly(
            LedgerResolution.NOT_PARKED,
            LedgerResolution.NOT_PARKED,
        )
    }

    @Test
    fun `a command the ops context's saga did not derive is refused, whoever its operator (D226)`(): Unit =
        runBlocking {
            parkAll("x1", start = false)
            // From another context's saga, or from no saga at all (a command written to the topic by hand).
            listOf(
                wire(skipOf(X1), source = "shop"),
                wire(skipOf(X1), source = null),
                wire(skipOf(X1), source = Ledger.OPS_CONTEXT, causation = null),
            ).forEach { refused ->
                val error = commands.execute(refused)!!
                error.code.assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
                error.message.assert().contains("ops")
            }
            ledger.parked(NAME, cart("x1"))!!.request.assert().isNull()
            sent.assert().isEmpty()
            // Another ops context, as wow.ops.publish.context names it.
            val elsewhere = LedgerCommands(ops, "platform-ops") { publisher }
            elsewhere.execute(wire(skipOf(X1), source = Ledger.OPS_CONTEXT))!!.code.assert()
                .isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
            elsewhere.execute(wire(skipOf(X1), source = "platform-ops")).assert().isNull()
            ledger.parked(NAME, cart("x1"))!!.request.assert().isEqualTo(LedgerRequest.SKIP)
        }

    @Test
    fun `a command that is unknown or does not read is a rejection`(): Unit = runBlocking {
        parkAll("x1", start = false)
        commands.execute(wire(Ledger.NAMESPACE + ".Unknown", ClusterCodec.mapper.createObjectNode()))!!.code
            .assert().isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
        commands.execute(wire(Ledger.RESUME, ClusterCodec.mapper.createObjectNode()))!!.code
            .assert().isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
        val blank = ClusterCodec.mapper.valueToTree<JsonNode>(
            ResumeParked.of(NAME, X1.type.context, X1.type.name, X1.tenantId, " ")
        )
        commands.execute(wire(Ledger.RESUME, blank))!!.code.assert().isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
        // A command from the ops saga is taken by its source, with or without an operator, whatever its roles.
        commands.execute(wire(skipOf(X1), operator = null)).assert().isNull()
        // The partition is another node's: the skip is left for it.
        ledger.parked(NAME, cart("x1"))!!.request.assert().isEqualTo(LedgerRequest.SKIP)
    }

    @Test
    fun `a node that does not publish its ledger still runs the commands`(): Unit = runBlocking {
        parkAll("x1", "x2", start = false)
        val quiet = LedgerCommands(ops)
        quiet.execute(wire(MarkParkedRecoverable.of(NAME, X1, "recoverable"))).assert().isNull()
        quiet.execute(wire(skipOf(X1))).assert().isNull()
        ledger.parked(NAME, cart("x1"))!!.request.assert().isEqualTo(LedgerRequest.SKIP)
        sent.assert().isEmpty()
    }

    private companion object {
        val CAUSATION = Causation("root-1", "ops.parked/(0)/entry-1@2")
        val PARKED_ENTRY: AggregateRef = me.ahoo.wow.api.aggregate.AggregateType("ops", "parked").ref("(0)", "entry-1")
        const val NAME = "Projector.onCart"
        val ALICE = Operator("alice", "t")
        val X1 = cart("x1")
    }
}
