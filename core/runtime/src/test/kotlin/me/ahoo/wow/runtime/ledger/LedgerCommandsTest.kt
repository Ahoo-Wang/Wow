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
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.ledger.ApplyParkedRetrySpec
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.ledger.LedgerAggregate
import me.ahoo.wow.api.ledger.LedgerCommand
import me.ahoo.wow.api.ledger.LedgerEntry
import me.ahoo.wow.api.ledger.LedgerResolution
import me.ahoo.wow.api.ledger.MarkParkedRecoverable
import me.ahoo.wow.api.ledger.ResumeParked
import me.ahoo.wow.api.ledger.SkipParked
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.runtime.FrameworkCodes
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.CommandWire
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
import me.ahoo.wow.spi.ledger.LedgerRequest
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

/** The ledger's system commands on the owning service (decision D141): authorized, run, and published. */
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
    private val spec = ParkedRetrySpec(maxRetries = 2, minBackoff = 1.hours, executionTimeout = 1.seconds)
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
    private val actions = CopyOnWriteArrayList<Pair<Caller, CommandAction>>()
    private val authorizer = CommandAuthorizer { caller, action ->
        actions += caller to action
        if (caller.subject == "mallory") Authorization.Denied("Not an operator.") else Authorization.Allowed
    }
    private val commands = LedgerCommands(ops, authorizer) { publisher }

    private suspend fun parkAll(vararg ids: String, start: Boolean = true) {
        ids.forEach { appender.append(it) }
        runtime.start(setOf(0, 1))
        eventually { ledger.list(NAME).size == ids.size }
        if (!start) runtime.stop()
    }

    private fun wire(command: LedgerCommand, operator: Operator? = ALICE): CommandWire = wire(
        Ledger.nameOf(command.javaClass)!!,
        ClusterCodec.mapper.valueToTree(command),
        command.aggregate.toRef(),
        operator,
    )

    private fun wire(type: String, body: JsonNode, ref: AggregateRef = cart("x1"), operator: Operator? = ALICE) =
        CommandWire(type, "c-1", "r-1", ref, body, scope = Scope.NONE, operator = operator)

    private fun lastRecord(): JsonNode = sent.last().record

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
            commands.execute(wire(ResumeParked(NAME, X1))).assert().isNull()
            commands.execute(wire(SkipParked(NAME, LedgerAggregate.of(cart("x2"))))).assert().isNull()
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
        commands.execute(wire(ResumeParked(NAME, X1))).assert().isNull()
        sent.last().record["outcome"].assert().isNull()
        ClusterCodec.mapper.treeToValue(lastRecord(), LedgerEntry::class.java).fromVersion.assert().isEqualTo(1)
        failing.set(false)
        commands.execute(wire(ResumeParked(NAME, X1))).assert().isNull()
        handled.assert().containsExactly("x1")
        ClusterCodec.mapper.treeToValue(lastRecord(), LedgerResolution::class.java).let {
            it.outcome.assert().isEqualTo(LedgerResolution.RESUMED)
            it.context.assert().isEqualTo("shop")
        }
        commands.execute(wire(ResumeParked(NAME, X1))).assert().isNull()
        lastRecord()["outcome"].asString().assert().isEqualTo(LedgerResolution.NOT_PARKED)
        val (caller, action) = actions.first()
        caller.subject.assert().isEqualTo("alice")
        caller.tenantId.assert().isEqualTo("t")
        action.type.name.assert().isEqualTo(Ledger.RESUME)
        action.type.type.assert().isEqualTo(ResumeParked::class.java)
        action.ref.assert().isEqualTo(cart("x1"))
        runtime.stop()
    }

    @Test
    fun `a skip resolves the entry, and one of a partition another node runs is left as a request`(): Unit =
        runBlocking {
            parkAll("x1", "x2")
            commands.execute(wire(SkipParked(NAME, X1))).assert().isNull()
            lastRecord()["outcome"].asString().assert().isEqualTo(LedgerResolution.SKIPPED)
            runtime.stop()
            commands.execute(wire(ResumeParked(NAME, LedgerAggregate.of(cart("x2"))))).assert().isNull()
            ledger.parked(NAME, cart("x2"))!!.request.assert().isEqualTo(LedgerRequest.RESUME)
            lastRecord()["request"].asString().assert().isEqualTo("RESUME")
        }

    @Test
    fun `an entry is marked and given a spec, each published as it now stands`(): Unit = runBlocking {
        parkAll("x1", start = false)
        commands.execute(wire(MarkParkedRecoverable(NAME, X1, "unrecoverable"))).assert().isNull()
        lastRecord()["status"].asString().assert().isEqualTo("UNRECOVERABLE")
        commands.execute(wire(MarkParkedRecoverable(NAME, X1, "maybe")))!!.code.assert()
            .isEqualTo(FrameworkCodes.MALFORMED)
        commands.execute(wire(MarkParkedRecoverable(NAME, X1, "RECOVERABLE"))).assert().isNull()
        ledger.parked(NAME, cart("x1"))!!.recoverable.assert().isEqualTo(Recoverable.RECOVERABLE)

        commands.execute(wire(ApplyParkedRetrySpec(NAME, X1, minBackoff = "PT5M", executionTimeout = "30s")))
            .assert().isNull()
        ledger.parked(NAME, cart("x1"))!!.retrySpec.assert()
            .isEqualTo(ParkedRetrySpec(maxRetries = 2, minBackoff = 5.minutes, executionTimeout = 30.seconds))
        lastRecord()["retrySpec"]["applied"].asBoolean().assert().isTrue()
        commands.execute(wire(ApplyParkedRetrySpec(NAME, X1, maxRetries = 0))).assert().isNull()
        ledger.parked(NAME, cart("x1"))!!.retrySpec!!.maxRetries.assert().isEqualTo(0)
        commands.execute(wire(ApplyParkedRetrySpec(NAME, X1, minBackoff = "soon")))!!.code.assert()
            .isEqualTo(FrameworkCodes.MALFORMED)
        commands.execute(wire(ApplyParkedRetrySpec(NAME, X1, maxRetries = -1)))!!.code.assert()
            .isEqualTo(FrameworkCodes.MALFORMED)

        val absent = LedgerAggregate.of(cart("absent"))
        sent.clear()
        commands.execute(wire(MarkParkedRecoverable(NAME, absent, "RECOVERABLE"))).assert().isNull()
        commands.execute(wire(ApplyParkedRetrySpec(NAME, absent, maxRetries = 1))).assert().isNull()
        sent.map { it.record["outcome"].asString() }.assert().containsExactly(
            LedgerResolution.NOT_PARKED,
            LedgerResolution.NOT_PARKED,
        )
    }

    @Test
    fun `a command that is denied, unknown or does not read is a rejection`(): Unit = runBlocking {
        parkAll("x1", start = false)
        commands.execute(wire(SkipParked(NAME, X1), Operator("mallory", "t")))!!.message.assert()
            .isEqualTo("Not an operator.")
        commands.execute(wire(Ledger.NAMESPACE + ".Unknown", ClusterCodec.mapper.createObjectNode()))!!.code
            .assert().isEqualTo(FrameworkCodes.MALFORMED)
        commands.execute(wire(Ledger.RESUME, ClusterCodec.mapper.createObjectNode()))!!.code
            .assert().isEqualTo(FrameworkCodes.MALFORMED)
        val blank = ClusterCodec.mapper.valueToTree<JsonNode>(ResumeParked(NAME, X1.copy(id = " ")))
        commands.execute(wire(Ledger.RESUME, blank))!!.code.assert().isEqualTo(FrameworkCodes.MALFORMED)
        // A command without an operator is authorized as the anonymous caller, in its scope.
        commands.execute(wire(SkipParked(NAME, X1), operator = null)).assert().isNull()
        actions.last().first.subject.assert().isNull()
        actions.last().first.tenantId.assert().isEqualTo(Caller.DEFAULT_TENANT)
        // The partition is another node's: the skip is left for it.
        ledger.parked(NAME, cart("x1"))!!.request.assert().isEqualTo(LedgerRequest.SKIP)
    }

    @Test
    fun `a node that does not publish its ledger still runs the commands`(): Unit = runBlocking {
        parkAll("x1", "x2", start = false)
        val quiet = LedgerCommands(ops)
        quiet.execute(wire(MarkParkedRecoverable(NAME, X1, "RECOVERABLE"))).assert().isNull()
        quiet.execute(wire(SkipParked(NAME, X1))).assert().isNull()
        ledger.parked(NAME, cart("x1"))!!.request.assert().isEqualTo(LedgerRequest.SKIP)
        sent.assert().isEmpty()
    }

    private companion object {
        const val NAME = "Projector.onCart"
        val ALICE = Operator("alice", "t")
        val X1 = LedgerAggregate.of(cart("x1"))
    }
}
