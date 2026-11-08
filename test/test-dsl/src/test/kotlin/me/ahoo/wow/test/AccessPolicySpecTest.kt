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

import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.api.access.AccessAttributes
import me.ahoo.wow.api.access.accessPolicy
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.kernel.aggregate
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
