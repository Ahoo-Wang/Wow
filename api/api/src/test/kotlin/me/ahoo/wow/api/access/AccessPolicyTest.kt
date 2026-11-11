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

package me.ahoo.wow.api.access

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.access.AccessPredicate.AllOf
import me.ahoo.wow.api.access.AccessPredicate.Allowed
import me.ahoo.wow.api.access.AccessPredicate.AnyOf
import me.ahoo.wow.api.access.AccessPredicate.Comparison
import me.ahoo.wow.api.access.AccessPredicate.Denied
import me.ahoo.wow.api.access.AccessPredicate.Not
import me.ahoo.wow.api.access.AccessPredicate.Unknown
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.caller.Caller
import org.junit.jupiter.api.Test

/** The access policy language: its build, its claims and its deny-by-default reading. */
class AccessPolicyTest {
    enum class Level { LOW, HIGH }

    class DocumentState

    class OtherState

    @Suppress("PropertyName", "VariableNaming")
    private object DocumentStateAccess : AccessAttributes<DocumentState>() {
        val DEPARTMENT: AccessAttribute.Single<DocumentState, String> =
            AccessAttribute.single("department", String::class.java)
        val ORG: AccessAttribute.Single<DocumentState, String> = AccessAttribute.single("org", String::class.java)
        val LEVEL: AccessAttribute.Single<DocumentState, Level> = AccessAttribute.single("level", Level::class.java)
        val SHARED_WITH: AccessAttribute.Multiple<DocumentState, String> =
            AccessAttribute.multiple("sharedWith", String::class.java)
    }

    private val bob = Caller(
        subject = "bob",
        attributes = mapOf(
            "departments" to listOf("sales", "hr"),
            "org" to listOf("/acme/sales"),
            "roles" to listOf("auditor")
        ),
    )

    private val documentAccess = accessPolicy<DocumentState> {
        read { caller ->
            anyOf(
                DocumentStateAccess.DEPARTMENT isIn caller.claims("departments"),
                DocumentStateAccess.SHARED_WITH contains caller.subject,
                DocumentStateAccess.ORG startsWith caller.claim("org"),
                allowIf(caller.hasRole("auditor")),
            )
        }
        write { caller -> DocumentStateAccess.DEPARTMENT isIn caller.claims("departments") }
    }

    /** A document's attribute values, as the kernel derives them: none absent but those not given. */
    private fun values(vararg values: Pair<AccessAttribute<*, *>, Any?>): (AccessAttribute<*, *>) -> Any? =
        values.toMap()::get

    @Test
    fun `a policy builds the caller's predicate, the claims in it`() {
        documentAccess.stateType.assert().isEqualTo(DocumentState::class.java)
        documentAccess.aggregate.assert().isNull()
        documentAccess.read(bob, Ownership.OWNER).assert().isEqualTo(
            AnyOf(
                listOf(
                    Comparison(DocumentStateAccess.DEPARTMENT, AccessOperator.IN, listOf("sales", "hr")),
                    Comparison(DocumentStateAccess.SHARED_WITH, AccessOperator.CONTAINS, listOf("bob")),
                    Comparison(DocumentStateAccess.ORG, AccessOperator.STARTS_WITH, listOf("/acme/sales")),
                    Allowed,
                ),
            ),
        )
        // A caller holding none of the claims: each comparison unknown, no role.
        documentAccess.read(
            Caller(),
            Ownership.OWNER
        ).assert().isEqualTo(AnyOf(listOf(Unknown, Unknown, Unknown, Denied)))
        documentAccess.write(Caller(attributes = mapOf("departments" to emptyList())), Ownership.OWNER)
            .assert().isEqualTo(Unknown)
        documentAccess.toString().assert().contains(DocumentState::class.java.name)
    }

    @Test
    fun `nothing is granted by omission`() {
        assertThrownBy<IllegalArgumentException> {
            accessPolicy<DocumentState> { read { allowAll() } }
        }.hasMessageContaining("no write predicate").hasMessageContaining("write { denyAll() }")
        assertThrownBy<IllegalArgumentException> {
            accessPolicy<DocumentState> { write { denyAll() } }
        }.hasMessageContaining("no read predicate")
        assertThrownBy<IllegalArgumentException> {
            accessPolicy<DocumentState> {
                read { allowAll() }
                readWrite { allowAll() }
            }
        }.hasMessageContaining("two read predicates")
        val shared = accessPolicy<DocumentState>(AggregateType("office", "archive")) {
            readWrite { caller -> DocumentStateAccess.OWNER eq caller.subject }
        }
        shared.aggregate.assert().isEqualTo(AggregateType("office", "archive"))
        shared.read(bob, Ownership.OWNER).assert().isEqualTo(shared.write(bob, Ownership.OWNER))
        accessPolicy<DocumentState> {
            read { allowAll() }
            write { denyAll() }
        }.write(bob, Ownership.OWNER).assert().isEqualTo(Denied)
    }

    @Test
    fun `the operators compare one value or several, and refuse the other kind`() {
        val predicates = accessPolicy<DocumentState> {
            readWrite {
                allOf(
                    DocumentStateAccess.LEVEL eq Level.HIGH,
                    DocumentStateAccess.DEPARTMENT.isNull(),
                    DocumentStateAccess.SHARED_WITH containsAny listOf("bob", "bob", "carol"),
                    DocumentStateAccess.SHARED_WITH.isEmpty(),
                    not(DocumentStateAccess.SPACE eq "s-1"),
                )
            }
        }.read(Caller(), Ownership.OWNER)
        (predicates as AllOf).predicates.assert().containsExactly(
            Comparison(DocumentStateAccess.LEVEL, AccessOperator.EQUAL, listOf(Level.HIGH)),
            Comparison(DocumentStateAccess.DEPARTMENT, AccessOperator.IS_NULL),
            Comparison(DocumentStateAccess.SHARED_WITH, AccessOperator.CONTAINS_ANY, listOf("bob", "carol")),
            Comparison(DocumentStateAccess.SHARED_WITH, AccessOperator.IS_EMPTY),
            Not(Comparison(AccessAttribute.SPACE, AccessOperator.EQUAL, listOf("s-1"))),
        )
        assertThrownBy<IllegalArgumentException> {
            Comparison(DocumentStateAccess.SHARED_WITH, AccessOperator.EQUAL, listOf("bob"))
        }
        assertThrownBy<IllegalArgumentException> {
            Comparison(DocumentStateAccess.DEPARTMENT, AccessOperator.IS_NULL, listOf("x"))
        }
        assertThrownBy<IllegalArgumentException> { Comparison(DocumentStateAccess.DEPARTMENT, AccessOperator.IN) }
    }

    @Test
    fun `a comparison holds of a present value, and an absent one or a missing claim grants neither way`() {
        val sales = Comparison(DocumentStateAccess.DEPARTMENT, AccessOperator.EQUAL, listOf("sales"))
        sales.allows(values(DocumentStateAccess.DEPARTMENT to "sales")).assert().isTrue()
        sales.allows(values(DocumentStateAccess.DEPARTMENT to "hr")).assert().isFalse()
        sales.allows(values()).assert().isFalse()
        Not(sales).allows(values(DocumentStateAccess.DEPARTMENT to "hr")).assert().isTrue()
        Not(sales).allows(values(DocumentStateAccess.DEPARTMENT to "sales")).assert().isFalse()
        // Absent: neither the comparison nor its negation grants.
        Not(sales).allows(values()).assert().isFalse()
        Unknown.allows(values()).assert().isFalse()
        Not(Unknown).allows(values()).assert().isFalse()
        Not(Not(sales)).allows(values(DocumentStateAccess.DEPARTMENT to "sales")).assert().isTrue()
        // The check of absence is the one comparison that holds of no value.
        val none = Comparison(DocumentStateAccess.DEPARTMENT, AccessOperator.IS_NULL)
        none.allows(values()).assert().isTrue()
        none.allows(values(DocumentStateAccess.DEPARTMENT to "hr")).assert().isFalse()
        Not(none).allows(values(DocumentStateAccess.DEPARTMENT to "hr")).assert().isTrue()
        Not(none).allows(values()).assert().isFalse()
    }

    @Test
    fun `each operator compares as it says`() {
        fun Comparison.of(value: Any?) = allows(values(attribute to value))
        Comparison(DocumentStateAccess.DEPARTMENT, AccessOperator.IN, listOf("sales", "hr")).of("hr").assert().isTrue()
        Comparison(DocumentStateAccess.DEPARTMENT, AccessOperator.IN, listOf("sales")).of("hr").assert().isFalse()
        Comparison(DocumentStateAccess.ORG, AccessOperator.STARTS_WITH, listOf("/acme"))
            .of("/acme/sales").assert().isTrue()
        Comparison(DocumentStateAccess.ORG, AccessOperator.STARTS_WITH, listOf("/acme/hr"))
            .of("/acme/sales").assert().isFalse()
        Comparison(DocumentStateAccess.LEVEL, AccessOperator.EQUAL, listOf(Level.HIGH)).of(Level.HIGH).assert().isTrue()
        val sharedWith = DocumentStateAccess.SHARED_WITH
        Comparison(sharedWith, AccessOperator.CONTAINS, listOf("bob")).of(setOf("bob", "carol")).assert().isTrue()
        Comparison(sharedWith, AccessOperator.CONTAINS, listOf("dave")).of(setOf("bob")).assert().isFalse()
        Comparison(sharedWith, AccessOperator.CONTAINS_ANY, listOf("dave", "bob")).of(setOf("bob")).assert().isTrue()
        Comparison(sharedWith, AccessOperator.CONTAINS_ANY, listOf("dave")).of(setOf("bob")).assert().isFalse()
        Comparison(sharedWith, AccessOperator.IS_EMPTY).of(emptySet<String>()).assert().isTrue()
        Comparison(sharedWith, AccessOperator.IS_EMPTY).of(setOf("bob")).assert().isFalse()
        // A record holding no values at all (not stamped): absent, so neither way.
        Comparison(sharedWith, AccessOperator.IS_EMPTY).of(null).assert().isFalse()
        Not(Comparison(sharedWith, AccessOperator.CONTAINS, listOf("bob"))).allows(values()).assert().isFalse()
    }

    @Test
    fun `logic combines them, and nothing given grants all or none`() {
        val hr = Comparison(DocumentStateAccess.DEPARTMENT, AccessOperator.EQUAL, listOf("hr"))
        val inHr = values(DocumentStateAccess.DEPARTMENT to "hr")
        AllOf(emptyList()).allows(inHr).assert().isTrue()
        AnyOf(emptyList()).allows(inHr).assert().isFalse()
        AllOf(listOf(hr, Allowed)).allows(inHr).assert().isTrue()
        AllOf(listOf(hr, Unknown)).allows(inHr).assert().isFalse()
        AnyOf(listOf(Unknown, hr)).allows(inHr).assert().isTrue()
        AnyOf(listOf(Denied, Unknown)).allows(inHr).assert().isFalse()
        // Negations pushed down: not(allOf(a, b)) is anyOf(not a, not b), each of a present value.
        Not(AllOf(listOf(hr, Denied))).allows(inHr).assert().isTrue()
        Not(AnyOf(listOf(hr, Denied))).allows(inHr).assert().isFalse()
        Not(AnyOf(listOf(Unknown, Denied))).allows(inHr).assert().isFalse()
        Not(Allowed).allows(inHr).assert().isFalse()
        Not(Denied).allows(inHr).assert().isTrue()
        listOf(Allowed, Denied, Unknown, Not(hr), AllOf(listOf(hr)), AnyOf(listOf(hr)), hr)
            .forEach { it.toString().assert().isNotBlank() }
        Not(hr).assert().isEqualTo(Not(Comparison(DocumentStateAccess.DEPARTMENT, AccessOperator.EQUAL, listOf("hr"))))
        Not(hr).hashCode().assert().isEqualTo(Not(hr).hashCode())
        AllOf(listOf(hr)).hashCode().assert().isEqualTo(AllOf(listOf(hr)).hashCode())
        AnyOf(listOf(hr)).hashCode().assert().isEqualTo(AnyOf(listOf(hr)).hashCode())
        hr.hashCode().assert().isEqualTo(
            Comparison(DocumentStateAccess.DEPARTMENT, AccessOperator.EQUAL, listOf("hr")).hashCode()
        )
    }

    @Test
    fun `predicates are equal by what they compare`() {
        val hr = Comparison(DocumentStateAccess.DEPARTMENT, AccessOperator.EQUAL, listOf("hr"))
        listOf(hr, Not(hr), AllOf(listOf(hr)), AnyOf(listOf(hr))).forEach { predicate ->
            (predicate == predicate).assert().isTrue()
            (predicate == Allowed).assert().isFalse()
        }
        (hr == Comparison(DocumentStateAccess.ORG, AccessOperator.EQUAL, listOf("hr"))).assert().isFalse()
        (hr == Comparison(DocumentStateAccess.DEPARTMENT, AccessOperator.IN, listOf("hr"))).assert().isFalse()
        (hr == Comparison(DocumentStateAccess.DEPARTMENT, AccessOperator.EQUAL, listOf("sales"))).assert().isFalse()
        (Not(hr) == Not(Allowed)).assert().isFalse()
        (AllOf(listOf(hr)) == AllOf(emptyList())).assert().isFalse()
        (AnyOf(listOf(hr)) == AnyOf(emptyList())).assert().isFalse()
        assertThrownBy<IllegalArgumentException> {
            Comparison(DocumentStateAccess.DEPARTMENT, AccessOperator.CONTAINS, listOf("hr"))
        }.hasMessageContaining("several values")
        assertThrownBy<IllegalArgumentException> {
            Comparison(DocumentStateAccess.SHARED_WITH, AccessOperator.EQUAL, listOf("bob"))
        }.hasMessageContaining("one value")
    }

    @Test
    fun `a policy built from the state's class, as Java builds it, gives one write predicate`() {
        val readOnly = accessPolicy(DocumentState::class.java) {
            read { allowAll() }
            write { denyAll() }
        }
        readOnly.aggregate.assert().isNull()
        readOnly.read(bob, Ownership.OWNER).assert().isEqualTo(Allowed)
        assertThrownBy<IllegalArgumentException> {
            accessPolicy(DocumentState::class.java) {
                write { denyAll() }
                readWrite { allowAll() }
            }
        }.hasMessageContaining("two write predicates")
    }

    @Test
    fun `the claims are read by their exact names`() {
        accessPolicy<DocumentState> {
            readWrite { caller ->
                allOf(
                    allowIf(caller.claim("org") == "/acme/sales"),
                    allowIf(caller.claims("departments") == listOf("sales", "hr")),
                    allowIf(caller.claims("abac.departments").isEmpty()),
                    allowIf(caller.claim("missing") == null),
                    allowIf(caller.hasRole("auditor") && !caller.hasRole("admin")),
                )
            }
        }.read(bob, Ownership.OWNER).assert().isEqualTo(AllOf(listOf(Allowed, Allowed, Allowed, Allowed, Allowed)))
        // A policy of another state takes the built-ins, which are every state's.
        accessPolicy<OtherState> { readWrite { caller -> AccessAttribute.OWNER eq caller.subject } }
            .read(
                bob,
                Ownership.OWNER
            ).assert().isEqualTo(Comparison(AccessAttribute.OWNER, AccessOperator.EQUAL, listOf("bob")))
    }
}
