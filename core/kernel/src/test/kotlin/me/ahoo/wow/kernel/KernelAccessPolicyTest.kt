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

package me.ahoo.wow.kernel

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.api.access.accessPolicy
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import org.junit.jupiter.api.Test

/**
 * A command under its aggregate's access policy (D390 E): `write` decides before deciding, `read` only which refusal
 * answers, and a creation or a change of the access attributes is checked again on the aggregate it leaves.
 */
class KernelAccessPolicyTest {
    enum class Level { OPEN, CLOSED }

    class Doc(val id: String) {
        var department: String? = null
        val sharedWith: MutableSet<String> = linkedSetOf()
        var level: Level = Level.CLOSED
        var title: String = ""
    }

    data class Write(val department: String, val level: Level = Level.CLOSED)

    data class Written(val department: String, val level: Level)

    data class Retitle(val title: String)

    data class Retitled(val title: String)

    data class Move(val department: String)

    data class Moved(val department: String)

    data class Share(val subject: String)

    data class Shared(val subject: String)

    private val department = AccessAttribute.single<Doc, String>("department", String::class.java)
    private val sharedWith = AccessAttribute.multiple<Doc, String>("sharedWith", String::class.java)
    private val level = AccessAttribute.single<Doc, Level>("level", Level::class.java)

    private val model = aggregate<Doc>("docs", "doc") {
        initial { Doc(it.id) }
        decide<Write>(Creation.REQUIRED) { _, body -> Decision.events(Written(body.department, body.level)) }
        decide<Retitle> { _, body -> Decision.events(Retitled(body.title)) }
        decide<Move> { _, body -> Decision.events(Moved(body.department)) }
        decide<Share> { _, body -> Decision.events(Shared(body.subject)) }
        evolve<Written> {
            department = it.department
            level = it.level
        }
        evolve<Retitled> { title = it.title }
        evolve<Moved> { department = it.department }
        evolve<Shared> { sharedWith += it.subject }
        access(department) { it.department }
        access(sharedWith) { it.sharedWith }
        access(level) { it.level }
    }

    /** A document is read by its department, those it is shared with, auditors, and anyone when open; changed by its department. */
    private val policy = accessPolicy(Doc::class.java) {
        read { caller ->
            anyOf(
                department isIn caller.claims("departments"),
                sharedWith contains caller.subject,
                level eq Level.OPEN,
                allowIf(caller.hasRole("auditor")),
            )
        }
        write { caller -> department isIn caller.claims("departments") }
    }

    private class MapCache : StateCache {
        val states = HashMap<AggregateRef, CachedState>()

        override fun take(ref: AggregateRef): CachedState? = states.remove(ref)

        override fun put(ref: AggregateRef, state: CachedState) {
            states[ref] = state
        }
    }

    private val log = InMemoryEventLog()
    private val cache = MapCache()
    private val kernel = Kernel(
        log,
        EventCodec(),
        states = cache,
        policies = { if (it == model.type) policy else null }
    )
    private val ref = model.type.ref("acme", "doc-1")
    private var sequence = 0

    private fun caller(subject: String, vararg departments: String, roles: List<String> = emptyList()) = Caller.of(
        "acme",
        Scope(subject),
        subject,
        attributes = mapOf("departments" to departments.toList(), "roles" to roles),
    )

    private val alice = caller("alice", "sales")
    private val carol = caller("carol", "hr")

    private fun command(
        body: Any,
        caller: Caller?,
        target: AggregateRef = ref,
        requestId: String? = null,
        causation: Causation? = null,
    ): Command<Any> {
        sequence++
        return Command(
            "c-$sequence",
            requestId ?: "r-$sequence",
            target,
            body,
            scope = caller?.scope ?: Scope.NONE,
            causation = causation,
            caller = caller,
        )
    }

    private suspend fun send(
        body: Any,
        caller: Caller?,
        target: AggregateRef = ref,
        requestId: String? = null,
        causation: Causation? = null,
    ): CommandOutcome = kernel.execute(model, command(body, caller, target, requestId, causation))

    private fun CommandOutcome.committed(): CommandOutcome.Committed = this as CommandOutcome.Committed

    private fun CommandOutcome.rejected(): CommandOutcome.Rejected = this as CommandOutcome.Rejected

    private suspend fun versions(target: AggregateRef = ref): List<Int> = log.read(target).toList().map { it.version }

    @Test
    fun `a member changes the document, and a caller who may not read it learns nothing of it`(): Unit = runTest {
        send(Write("sales"), alice).committed()
        send(Retitle("plan"), alice).committed().stream.version.assert().isEqualTo(2)
        val missing = send(Retitle("x"), carol, target = model.type.ref("acme", "doc-none")).rejected()
        val refused = send(Retitle("x"), carol).rejected()
        refused.rejection.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_NOT_FOUND)
        refused.rejection.message.assert().isEqualTo("$ref does not exist.")
        missing.rejection.message.assert().isEqualTo("${model.type.ref("acme", "doc-none")} does not exist.")
        versions().assert().containsExactly(1, 2)
    }

    @Test
    fun `a caller who may read the document but not change it is forbidden`(): Unit = runTest {
        send(Write("sales"), alice).committed()
        send(Share("bob"), alice).committed()
        send(
            Retitle("x"),
            caller("bob")
        ).rejected().rejection.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_FORBIDDEN)
        send(Retitle("x"), caller("dora", roles = listOf("auditor"))).rejected().rejection.code.assert()
            .isEqualTo(FrameworkCodes.AGGREGATE_FORBIDDEN)
        versions().assert().containsExactly(1, 2)
    }

    @Test
    fun `a creation out of the caller's reach is forbidden and appends nothing`(): Unit = runTest {
        send(Write("hr"), alice).rejected().rejection.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_FORBIDDEN)
        versions().assert().isEmpty()
        send(Write("sales"), alice).committed()
        // A creation that collides with a document the caller may not read tells only that it exists: forbidden.
        send(Write("hr"), carol).rejected().rejection.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_FORBIDDEN)
        versions().assert().containsExactly(1)
    }

    @Test
    fun `a decision that moves the document out of the caller's reach is refused, and its evolved state dropped`():
        Unit = runTest {
        send(Write("sales"), alice).committed()
        send(Move("hr"), alice).rejected().rejection.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_FORBIDDEN)
        versions().assert().containsExactly(1)
        // The state the refused decision evolved is not cached: the next command decides on the committed one.
        cache.states[ref].assert().isNull()
        (send(Retitle("plan"), alice).committed().state as Doc).department.assert().isEqualTo("sales")
        // A move within the caller's reach commits.
        send(Move("legal"), caller("alice", "sales", "legal")).committed().stream.version.assert().isEqualTo(3)
    }

    @Test
    fun `a writer transfers the owner, which gives the document away on purpose and is not checked after`():
        Unit = runTest {
        send(Write("sales"), alice).committed()
        send(TransferOwner.of("zed"), alice).committed().stream.scope.assert().isEqualTo(Scope("zed"))
        // The policy, not the owner, decides who changes it.
        send(Retitle("plan"), alice).committed()
    }

    @Test
    fun `derived commands and services are trusted, and a command without a caller reaches nothing`(): Unit = runTest {
        send(Write("sales"), alice).committed()
        send(Retitle("x"), null, causation = Causation("o-1", "orders.order/acme/o-1@1")).committed()
        send(Retitle("y"), Caller.ofService("reports", "acme")).committed()
        send(Retitle("z"), null).rejected().rejection.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_NOT_FOUND)
        val other = model.type.ref("acme", "doc-2")
        send(Write("sales"), null, target = other).rejected().rejection.code.assert()
            .isEqualTo(FrameworkCodes.AGGREGATE_FORBIDDEN)
        versions(other).assert().isEmpty()
    }

    @Test
    fun `a retry is answered to a caller who may read the document by the attributes its stream records`(): Unit =
        runTest {
            val open = model.type.ref("acme", "doc-open")
            val written = send(Write("sales", Level.OPEN), alice, target = open, requestId = "open").committed()
            send(Write("sales", Level.OPEN), carol, target = open, requestId = "open")
                .assert().isEqualTo(CommandOutcome.AlreadyCommitted(written.stream))
            send(Write("sales"), alice, requestId = "closed").committed()
            send(Write("sales"), carol, requestId = "closed").rejected().rejection.code.assert()
                .isEqualTo(FrameworkCodes.AGGREGATE_NOT_FOUND)
        }

    @Test
    fun `a batch member refused once decided ends the batch, and is refused again alone`(): Unit = runTest {
        send(Write("sales"), alice).committed()
        val outcomes = kernel.executeBatch(
            model,
            listOf(Retitle("a"), Move("hr"), Retitle("b")).map { QueuedCommand(command(it, alice)) },
        )
        ((outcomes[0] as BatchOutcome.Done).outcome as CommandOutcome.Committed).stream.version.assert().isEqualTo(2)
        outcomes.drop(1).assert().containsOnly(BatchOutcome.Again)
        send(Move("hr"), alice).rejected().rejection.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_FORBIDDEN)
        (send(Retitle("b"), alice).committed().state as Doc).department.assert().isEqualTo("sales")
        versions().assert().containsExactly(1, 2, 3)
    }
}
