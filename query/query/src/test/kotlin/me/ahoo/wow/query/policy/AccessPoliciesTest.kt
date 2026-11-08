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

package me.ahoo.wow.query.policy

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.api.access.AccessAttributes
import me.ahoo.wow.api.access.AccessPolicy
import me.ahoo.wow.api.access.accessPolicy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasetAccess
import me.ahoo.wow.query.dataset.QueryDatasetScope
import me.ahoo.wow.query.dataset.QueryDatasetTest
import me.ahoo.wow.query.policy.AccessFixtures.DEPARTMENT
import me.ahoo.wow.query.policy.AccessFixtures.DOCUMENT
import me.ahoo.wow.query.policy.AccessFixtures.DocumentState
import me.ahoo.wow.query.policy.AccessFixtures.LEDGER
import me.ahoo.wow.query.policy.AccessFixtures.LEVEL
import me.ahoo.wow.query.policy.AccessFixtures.MANIFEST
import me.ahoo.wow.query.policy.AccessFixtures.NOTE
import me.ahoo.wow.query.policy.AccessFixtures.NoteState
import me.ahoo.wow.query.policy.AccessFixtures.REGION
import me.ahoo.wow.query.policy.AccessFixtures.documents
import me.ahoo.wow.query.policy.AccessFixtures.policies
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The startup check of the access policies against the Manifest (D390 B2). */
class AccessPoliciesTest {
    private val notes = accessPolicy<NoteState> { readWrite { allowAll() } }
    private val missing = AggregateType("docs", "missing")

    private class UnknownState

    private class SharedState

    private enum class Level { LOW }

    /** [policies] of the documents' service on a node of [contexts], each of the aggregates [aggregatesOf] gives. */
    private fun of(
        vararg policies: AccessPolicy<*>,
        contexts: Set<String> = setOf("docs"),
        strict: Boolean = true,
        aggregatesOf: (Class<*>) -> List<AggregateType> = AccessFixtures::aggregatesOf,
    ): AccessPolicies = AccessPolicies.of(MANIFEST, contexts, policies.toList(), aggregatesOf, strict)

    @Test
    fun `an aggregate that declares access attributes should not start without a policy`() {
        assertThrows<IllegalStateException> { policies() }.message.assert()
            .contains("docs.document declares the access attributes [department, sharedWith, level]")
            .contains("nothing is granted by omission")
    }

    @Test
    fun `each policy should govern the aggregate of its state, the others reading by the default rule`() {
        val access = policies(documents, notes)
        access.governed.assert().containsExactlyInAnyOrder(DOCUMENT, NOTE)
        access.of(NOTE).assert().isSameAs(notes)
        access.governs(LEDGER).assert().isFalse()
        access.of(LEDGER).assert().isNull()
        AccessPolicies.NONE.governed.assert().isEmpty()
    }

    @Test
    fun `a policy that compares only the owner and the space should govern its aggregate as any other`() {
        val owners = accessPolicy<NoteState> {
            read { caller -> anyOf(NoteAccess.OWNER eq caller.subject, NoteAccess.SPACE isIn caller.claims("spaces")) }
            write { denyAll() }
        }
        val access = policies(documents, owners)
        access.governs(NOTE).assert().isTrue()
        // What the host gives a Sink to record: the reader elsewhere cannot evaluate it either.
        val governed = GovernedAggregates(access::governs)
        governed.governs(NOTE).assert().isTrue()
        governed.governs(LEDGER).assert().isFalse()
    }

    @Test
    fun `a policy of no aggregate of the node should be refused, unless the node hosts some of the contexts`() {
        val unknown = accessPolicy<UnknownState> { readWrite { allowAll() } }
        assertThrows<IllegalStateException> { policies(documents, unknown) }.message.assert()
            .contains("is of no aggregate of this node")
        of(documents, unknown, strict = false).governed.assert().containsExactly(DOCUMENT)
        // A node of none of the documents' contexts leaves their policy to the node that hosts them.
        of(documents, contexts = emptySet(), strict = false).governed.assert().isEmpty()
        val named = accessPolicy<UnknownState>(aggregate = missing) { readWrite { allowAll() } }
        of(documents, named, strict = false).governed.assert().containsExactly(DOCUMENT)
    }

    @Test
    fun `a policy of a state several aggregates share should name the one it governs`() {
        val aggregatesOf = { type: Class<*> ->
            if (type == SharedState::class.java) listOf(NOTE, LEDGER) else AccessFixtures.aggregatesOf(type)
        }
        val shared = accessPolicy<SharedState> { readWrite { allowAll() } }
        assertThrows<IllegalStateException> { of(documents, shared, aggregatesOf = aggregatesOf) }.message.assert()
            .contains("is of the state of several aggregates")
            .contains("accessPolicy(aggregate = …)")
        val ledgers = accessPolicy<SharedState>(aggregate = LEDGER) { readWrite { allowAll() } }
        of(documents, ledgers, aggregatesOf = aggregatesOf).of(LEDGER).assert().isSameAs(ledgers)
        val wrong = accessPolicy<SharedState>(aggregate = DOCUMENT) { readWrite { allowAll() } }
        assertThrows<IllegalStateException> { of(documents, wrong, aggregatesOf = aggregatesOf) }.message.assert()
            .contains("names docs.document, which is not an aggregate of this node whose state is")
        // Its state's aggregates are this node's: naming another is a mistake, whichever contexts the node hosts.
        assertThrows<IllegalStateException> { of(documents, wrong, strict = false, aggregatesOf = aggregatesOf) }
    }

    @Test
    fun `two policies of one aggregate should be refused`() {
        val again = accessPolicy<DocumentState> { readWrite { allowAll() } }
        assertThrows<IllegalStateException> { policies(documents, again) }.message.assert()
            .contains("docs.document has 2 access policies")
    }

    @Test
    fun `a policy may compare the attributes its aggregate has, each by its name, type and multiplicity`() {
        val access = policies(documents)
        access.declares(DOCUMENT, DEPARTMENT).assert().isTrue()
        access.declares(DOCUMENT, LEVEL).assert().isTrue()
        access.declares(DOCUMENT, single<Long>("level", Long::class.javaObjectType)).assert().isTrue()
        access.declares(DOCUMENT, REGION).assert().isFalse()
        access.declares(DOCUMENT, AccessAttribute.multiple<DocumentState, String>("department", String::class.java))
            .assert().isFalse()
        access.declares(DOCUMENT, single<Boolean>("department", Boolean::class.javaObjectType)).assert().isFalse()
        // An enum is written as a string.
        access.declares(DOCUMENT, single<Level>("department", Level::class.java)).assert().isTrue()
        access.declares(NOTE, AccessAttribute.OWNER).assert().isTrue()
        access.declares(LEDGER, AccessAttribute.OWNER).assert().isFalse()
        access.declares(LEDGER, AccessAttribute.SPACE).assert().isTrue()
        access.declares(missing, AccessAttribute.SPACE).assert().isFalse()
        access.has(DOCUMENT).assert().isTrue()
        access.has(missing).assert().isFalse()
    }

    @Test
    fun `a dataset bound to a governed aggregate should be governed by its policy`() {
        val access = policies(documents)
        val orders = QueryDatasetTest.orders()
        fun bound(aggregate: AggregateType) = QueryDataset(
            orders.name,
            QueryDatasetScope.of(
                tenant = orders.scope.tenant,
                access = QueryDatasetAccess.of(aggregate, QueryField("order_id")),
            ),
            orders.declaration,
        )
        access.governs(orders).assert().isFalse()
        access.governs(bound(DOCUMENT)).assert().isTrue()
        access.governs(bound(NOTE)).assert().isFalse()
    }

    private fun <V : Any> single(name: String, type: Class<V>): AccessAttribute.Single<DocumentState, V> =
        AccessAttribute.single(name, type)
}

/** A note's built-in attributes, the only ones it has. */
private object NoteAccess : AccessAttributes<NoteState>()
