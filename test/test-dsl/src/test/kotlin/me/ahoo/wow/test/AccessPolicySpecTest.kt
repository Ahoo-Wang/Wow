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

package me.ahoo.wow.test

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.api.access.AccessAttributes
import me.ahoo.wow.api.access.accessPolicy
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.WritePolicies
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import org.junit.jupiter.api.Test

/** `accessPolicySpec`: a policy checked on an aggregate for a caller, as the kernel and the queries decide. */
class AccessPolicySpecTest {
    data class Created(val department: String)
    data class Shared(val with: String)

    class Document(val id: String) {
        var department: String? = null
        val sharedWith: MutableSet<String> = linkedSetOf()
    }

    @Suppress("PropertyName", "VariableNaming")
    private object DocumentAccess : AccessAttributes<Document>() {
        val DEPARTMENT: AccessAttribute.Single<Document, String> = AccessAttribute.single(
            "department",
            String::class.java
        )
        val SHARED_WITH: AccessAttribute.Multiple<Document, String> =
            AccessAttribute.multiple("sharedWith", String::class.java)
        val UNDECLARED: AccessAttribute.Single<Document, String> = AccessAttribute.single("region", String::class.java)
    }

    private val model = aggregate<Document>("office", "document") {
        initial { Document(it.id) }
        evolve<Created> { department = it.department }
        evolve<Shared> { sharedWith += it.with }
        access(DocumentAccess.DEPARTMENT) { it.department }
        access(DocumentAccess.SHARED_WITH) { it.sharedWith }
    }

    // region policy
    private val documentAccess = accessPolicy<Document> {
        read { caller ->
            anyOf(
                DocumentAccess.DEPARTMENT isIn caller.claims("departments"),
                DocumentAccess.SHARED_WITH contains caller.subject,
                DocumentAccess.OWNER eq caller.subject,
                allowIf(caller.hasRole("auditor")),
            )
        }
        write { caller -> DocumentAccess.DEPARTMENT isIn caller.claims("departments") }
    }
    // endregion

    // region spec
    @Test
    fun `a caller reads and changes what the policy grants on the aggregate its events leave`() =
        accessPolicySpec(model, documentAccess) {
            given(Created("sales"), Shared("bob"))
            whenCaller(subject = "bob", claims = mapOf("departments" to listOf("hr"))) {
                expectRead()
                expectNoWrite()
            }
            whenCaller(subject = "carol", claims = mapOf("departments" to listOf("sales"))) {
                expectRead()
                expectWrite()
            }
            whenCaller(subject = "dave") {
                expectNoRead()
                expectNoWrite()
            }
            whenCaller(subject = "erin", roles = listOf("auditor")) { expectRead() }
        }
    // endregion

    @Test
    fun `the built-in owner and space are the aggregate's scope`() = accessPolicySpec(model, documentAccess) {
        givenState(Document("d-1"), Scope("alice", "s-1"))
        whenCaller(Caller(subject = "alice")) { expectRead() }
        whenCaller(subject = "bob") { expectNoRead() }
    }

    @Test
    fun `a policy of the space, a negation and a conjunction is checked on the scope and the values`() {
        val inSpace = accessPolicy<Document> {
            read { caller ->
                allOf(DocumentAccess.SPACE eq caller.claim("space"), not(DocumentAccess.DEPARTMENT eq "hr"))
            }
            write { denyAll() }
        }
        accessPolicySpec(model, inSpace) {
            givenState(Document("d-1").apply { department = "sales" })
            whenCaller(claims = mapOf("space" to listOf("s-1"))) { expectNoRead() }
            given(Created("sales"), scope = Scope(spaceId = "s-1"))
            whenCaller(claims = mapOf("space" to listOf("s-1"))) {
                expectRead()
                expectNoWrite()
            }
            given(Created("hr"), scope = Scope(spaceId = "s-1"))
            whenCaller(claims = mapOf("space" to listOf("s-1"))) { expectNoRead() }
        }
    }

    @Test
    fun `a check that does not hold says what was compared`() = accessPolicySpec(model, documentAccess) {
        given(Created("sales"))
        whenCaller(subject = "dave") {
            assertThrownBy<AssertionError> { expectRead() }
                .hasMessageContaining("Expected the caller to read the aggregate, but it may not")
                .hasMessageContaining("department=sales")
            assertThrownBy<AssertionError> { expectNoWrite().also { expectWrite() } }
                .hasMessageContaining("Expected the caller to change the aggregate")
        }
        whenCaller(subject = "carol", claims = mapOf("departments" to listOf("sales"))) {
            assertThrownBy<AssertionError> { expectNoRead() }.hasMessageContaining(
                "not to read the aggregate, but it may"
            )
        }
    }

    data class Draft(val department: String)
    data class Edit(val text: String)
    data class Edited(val text: String)

    /** The document with its commands decided: drafted in a department, then edited. */
    private val decided = aggregate<Document>("office", "document") {
        initial { Document(it.id) }
        decide<Draft>(Creation.REQUIRED) { _, body -> Decision.events(Created(body.department)) }
        decide<Edit> { _, body -> Decision.events(Edited(body.text)) }
        evolve<Created> { department = it.department }
        evolve<Shared> { sharedWith += it.with }
        evolve<Edited> { }
        access(DocumentAccess.DEPARTMENT) { it.department }
        access(DocumentAccess.SHARED_WITH) { it.sharedWith }
    }

    private fun member(department: String) = Caller.of(
        scope = Scope("alice"),
        subject = "alice",
        attributes = mapOf("departments" to listOf(department)),
    )

    /** What a node's kernel, given the service's [documentAccess], answers alice's Edit once she is of hr. */
    private fun onNode(): CommandOutcome = runBlocking {
        val kernel = Kernel(
            InMemoryEventLog(partitions = 1),
            policies = WritePolicies.of(listOf(documentAccess), listOf(decided)),
        )
        val ref = decided.type.ref(DEFAULT_TENANT, "aggregate-1")
        val sales = member("sales")
        kernel.execute(decided, Command("c-1", "r-1", ref, Draft("sales"), scope = sales.scope, caller = sales))
        val hr = member("hr")
        kernel.execute(decided, Command("c-2", "r-2", ref, Edit("moved"), scope = hr.scope, caller = hr))
    }

    @Test
    fun `a command the aggregate's access policy denies should be refused by aggregateSpec, as by a node's kernel`() {
        // As on a node: the kernel decides under the service's policy on the claims of the caller the gateway sets on
        // the command (CommandReach). Alice, now of hr, may read her sales document as its owner, but not change it.
        // Moved from review/r1-testkit (DslReviewR1Test), where the spec's kernel had no policy and committed it.
        (onNode() as CommandOutcome.Rejected).rejection.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_FORBIDDEN)
        aggregateSpec(decided, caller = member("sales"), policy = documentAccess) {
            givenCommands(Draft("sales"))
            whenCommand(Edit("typo")) { expectEventTypes(Edited::class) }
            asCaller(member("hr")) {
                whenCommand(Edit("moved")) { expectRejected(FrameworkCodes.AGGREGATE_FORBIDDEN) }
            }
            // A fork keeps the policy; a service is trusted under it.
            fork("by hr") {
                asCaller(member("hr")) {
                    whenCommand(Edit("moved")) { expectRejected(FrameworkCodes.AGGREGATE_FORBIDDEN) }
                }
            }
            whenCommand(Edit("billed"), caller = Caller.ofService("billing")) { expectEventTypes(Edited::class) }
        }
        // Without the policy the default scope rule decides: alice's owner scope changes it, whatever her department.
        aggregateSpec(decided, caller = member("sales")) {
            givenCommands(Draft("sales"))
            asCaller(member("hr")) { whenCommand(Edit("moved")) { expectEventTypes(Edited::class) } }
        }
        // A command without a caller is the anonymous caller's, which holds no claim: nothing is granted by omission.
        aggregateSpec(decided, policy = documentAccess) {
            given(Created("sales"))
            whenCommand(Edit("anyone")) { expectRejected(FrameworkCodes.AGGREGATE_NOT_FOUND) }
        }
    }

    @Test
    fun `a service caller should change an aggregate its policy governs, as the kernel trusts a service`() =
        accessPolicySpec(model, documentAccess) {
            // As the kernel (CommandReach) and the queries decide: under a policy, a service acting as itself is
            // trusted, whatever the policy's predicates say of its claims.
            given(Created("sales"))
            whenCaller(Caller.ofService("billing")) {
                expectWrite()
                expectRead()
                assertThrownBy<AssertionError> { expectNoWrite() }
                    .hasMessageContaining("Expected the caller not to change the aggregate, but it may")
                    .hasMessageContaining("is a service")
            }
        }

    @Test
    fun `a policy comparing an attribute the model does not declare fails, and one of another state is refused`() {
        val undeclared =
            accessPolicy<Document> { readWrite { caller -> DocumentAccess.UNDECLARED eq caller.claim("region") } }
        accessPolicySpec(model, undeclared) {
            whenCaller(Caller(attributes = mapOf("region" to listOf("eu")))) {
                assertThrownBy<AssertionError> { expectNoRead() }.hasMessageContaining("[region]")
            }
        }
        val other = aggregate<String>("office", "note") { initial { it.id } }
        @Suppress("UNCHECKED_CAST")
        assertThrownBy<IllegalArgumentException> {
            accessPolicySpec(other, documentAccess as me.ahoo.wow.api.access.AccessPolicy<String>) { }
        }.hasMessageContaining("not a policy of office.note")
    }
}
