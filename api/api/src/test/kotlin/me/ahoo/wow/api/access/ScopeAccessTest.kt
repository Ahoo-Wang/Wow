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
import me.ahoo.wow.api.access.AccessPredicate.AllOf
import me.ahoo.wow.api.access.AccessPredicate.Allowed
import me.ahoo.wow.api.access.AccessPredicate.Comparison
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import org.junit.jupiter.api.Test

/** The default rule: each part of the caller's scope is the aggregate's, a part it does not name unchecked. */
class ScopeAccessTest {
    private val owner = Comparison(AccessAttribute.OWNER, AccessOperator.EQUAL, listOf("alice"))
    private val space = Comparison(AccessAttribute.SPACE, AccessOperator.EQUAL, listOf("s1"))

    @Test
    fun `the rule compares each part the scope names`() {
        ScopeAccess.of(Scope.NONE).assert().isSameAs(Allowed)
        ScopeAccess.of(Scope("alice")).assert().isEqualTo(owner)
        ScopeAccess.of(Scope(spaceId = "s1")).assert().isEqualTo(space)
        ScopeAccess.of(Scope("alice", "s1")).assert().isEqualTo(AllOf(listOf(owner, space)))
    }

    @Test
    fun `an aggregate is reached when it has each part the scope names`() {
        val rule = ScopeAccess.of(Scope("alice", "s1"))
        ScopeAccess.allows(rule, Scope("alice", "s1")).assert().isTrue()
        ScopeAccess.allows(rule, Scope("alice", "s2")).assert().isFalse()
        ScopeAccess.allows(rule, Scope("bob", "s1")).assert().isFalse()
        // A part the aggregate lacks is not the caller's.
        ScopeAccess.allows(rule, Scope("alice")).assert().isFalse()
        ScopeAccess.allows(ScopeAccess.of(Scope("alice")), Scope("alice", "s2")).assert().isTrue()
        ScopeAccess.allows(ScopeAccess.of(Scope.NONE), Scope.NONE).assert().isTrue()
    }

    @Test
    fun `an aggregate's scope holds the built-in attributes alone`() {
        val aggregate = Scope("alice", "s1")
        ScopeAccess.valueOf(AccessAttribute.OWNER, aggregate).assert().isEqualTo("alice")
        ScopeAccess.valueOf(AccessAttribute.SPACE, aggregate).assert().isEqualTo("s1")
        ScopeAccess.valueOf(AccessAttribute.single<Any, String>("region", String::class.java), aggregate)
            .assert().isNull()
    }

    @Test
    fun `a policy keeps the default rule by saying so, as the aggregate's ownership keeps the scope`() {
        val policy = accessPolicy<Note> { readWrite { caller -> ownerAndSpace(caller) } }
        val caller = Caller.of(scope = Scope("alice", "s1"), subject = "alice")
        policy.read(caller, Ownership.OWNER).assert().isEqualTo(ScopeAccess.of(Scope("alice", "s1")))
        policy.write(caller, Ownership.OWNER).assert().isEqualTo(ScopeAccess.of(Scope("alice", "s1")))
        // An aggregate of its tenant records no owner: the space alone is compared, for reads and changes alike.
        policy.read(caller, Ownership.TENANT).assert().isEqualTo(ScopeAccess.of(Scope(spaceId = "s1")))
        policy.write(caller, Ownership.TENANT).assert().isEqualTo(ScopeAccess.of(Scope(spaceId = "s1")))
    }

    @Test
    fun `an owner-level caller reaches an aggregate of its tenant in its space by the policy's default rule`() {
        val policy = accessPolicy<Note> { readWrite { caller -> ownerAndSpace(caller) } }
        val caller = Caller.of(scope = Scope("alice", "s1"), subject = "alice")
        // The aggregate records no owner, as a tenant's aggregate does.
        val aggregate = Scope(spaceId = "s1")
        val read = policy.read(caller, Ownership.TENANT)
        read.allows { ScopeAccess.valueOf(it, aggregate) }.assert().isTrue()
        policy.read(caller, Ownership.TENANT).allows { ScopeAccess.valueOf(it, Scope(spaceId = "s2")) }
            .assert().isFalse()
        // Compared as an owner's aggregate, the caller's owner finds none on it.
        policy.read(caller, Ownership.OWNER).allows { ScopeAccess.valueOf(it, aggregate) }.assert().isFalse()
    }

    class Note
}
