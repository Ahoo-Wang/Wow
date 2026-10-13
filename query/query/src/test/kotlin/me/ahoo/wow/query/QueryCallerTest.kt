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

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import org.junit.jupiter.api.Test

class QueryCallerTest {
    @Test
    fun `scope accumulates with AND and never changes the original caller`() {
        val original = QueryCaller(Caller(subject = "alice"))
        val tenant = TenantIdFilter("tenant")
        val owner = OwnerIdFilter("owner")
        val scoped = original.withScope(QueryScope(declared = tenant))
            .withScope(QueryScope(declared = owner))
            .withScope(QueryScope.NONE)

        original.scope.filter.assert().isSameAs(MatchAllFilter)
        scoped.scope.filter.assert().isEqualTo(AndFilter(listOf(tenant, owner)))
        scoped.subject.assert().isEqualTo("alice")
    }

    @Test
    fun `the scope keeps its authenticated and declared halves apart`() {
        val tenant = TenantIdFilter("tenant")
        val owner = OwnerIdFilter("owner")
        val space = SpaceIdFilter("space")
        val scope = QueryScope(authenticated = tenant).append(QueryScope(declared = owner))
            .append(QueryScope(authenticated = space))

        scope.authenticated.assert().isEqualTo(AndFilter(listOf(tenant, space)))
        scope.declared.assert().isEqualTo(owner)
        scope.filter.assert().isEqualTo(AndFilter(listOf(AndFilter(listOf(tenant, space)), owner)))
        // A match-all half adds no node.
        QueryScope(authenticated = tenant).filter.assert().isSameAs(tenant)
        QueryScope(declared = owner).filter.assert().isSameAs(owner)
        QueryScope.NONE.filter.assert().isSameAs(MatchAllFilter)
        QueryScope.NONE.append(QueryScope.NONE).assert().isEqualTo(QueryScope.NONE)
        QueryScopeProvenance.entries.assert().containsExactly(
            QueryScopeProvenance.AUTHENTICATED,
            QueryScopeProvenance.DECLARED,
        )
    }

    @Test
    fun `entry is unspecified until stated and in-process queries drop the caller, scope and entry`() {
        val http = QueryCaller(
            Caller(subject = "alice", attributes = mapOf("abac.dept" to listOf("eng"))),
            entry = QueryEntry.HTTP,
        ).withScope(QueryScope(declared = TenantIdFilter("tenant"))).withSelection(IdFilter("a1"))
        QueryCaller().entry.assert().isEqualTo(QueryEntry.UNSPECIFIED)
        QueryCaller.UNSPECIFIED.entry.assert().isEqualTo(QueryEntry.UNSPECIFIED)
        QueryCaller.IN_PROCESS.entry.assert().isEqualTo(QueryEntry.IN_PROCESS)
        QueryCaller.IN_PROCESS.scope.assert().isEqualTo(QueryScope.NONE)
        http.entry.assert().isEqualTo(QueryEntry.HTTP)

        val nested = http.inProcess()
        nested.entry.assert().isEqualTo(QueryEntry.IN_PROCESS)
        nested.scope.filter.assert().isSameAs(MatchAllFilter)
        nested.selection.assert().isSameAs(MatchAllFilter)
        nested.caller.assert().isNull()
        nested.subject.assert().isNull()
        nested.attributes.assert().isEmpty()
        http.subject.assert().isEqualTo("alice")
        http.tenantId.assert().isEqualTo(Caller.DEFAULT_TENANT)
    }

    @Test
    fun `a route selection accumulates apart from the scope`() {
        val caller = QueryCaller.UNSPECIFIED.withSelection(IdFilter("a1"))
        caller.selection.assert().isEqualTo(IdFilter("a1"))
        caller.scope.filter.assert().isSameAs(MatchAllFilter)
        caller.withSelection(MatchAllFilter).assert().isSameAs(caller)
        caller.withSelection(OwnerIdFilter("o1")).selection
            .assert().isEqualTo(AndFilter(listOf(IdFilter("a1"), OwnerIdFilter("o1"))))
    }
}
