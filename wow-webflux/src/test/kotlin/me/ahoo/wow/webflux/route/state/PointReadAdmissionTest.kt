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

package me.ahoo.wow.webflux.route.state

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.query.QueryScope
import me.ahoo.wow.query.QueryScopeRequiredException
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import tools.jackson.databind.node.LongNode
import tools.jackson.databind.node.NullNode

class PointReadAdmissionTest {
    private val state = MOCK_AGGREGATE_METADATA.toStateAggregate(
        state = MockStateAggregate("a1"),
        version = 1,
        ownerId = "owner",
        spaceId = "space",
        tenantId = "tenant",
    )
    private val request = MockServerRequest.builder().build()
    private val schema = QueryModelSchema(
        QueryModel.SNAPSHOT,
        emptySet(),
        LogicalQuerySchema(QueryValueSchema(QueryValueKind.OBJECT)),
        emptyMap(),
    )

    private fun admission(
        scope: QueryScope = QueryScope.NONE,
        policies: List<QueryPolicy> = emptyList(),
        withSchema: Boolean = true,
        entryPolicy: QueryEntryPolicy = QueryEntryPolicy.DEFAULT,
    ) = PointReadAdmission(
        enabled = true,
        queryRequestScope = { _, _ -> scope },
        policies = policies,
        snapshotSchema = if (withSchema) ({ Mono.just(schema) }) else null,
        entryPolicy = entryPolicy,
    )

    private fun reads(admission: PointReadAdmission): Boolean =
        admission.read(MOCK_AGGREGATE_METADATA, request, state).blockOptional().isPresent

    @Test
    fun `the scope is checked against the state record`() {
        reads(admission()).assert().isTrue()
        reads(
            admission(
                QueryScope(
                    authenticated = TenantIdFilter("tenant"),
                    declared = AndFilter(listOf(OwnerIdFilter("owner"), SpaceIdFilter("space"))),
                )
            )
        ).assert().isTrue()
        reads(admission(QueryScope(declared = OwnerIdFilter("other")))).assert().isFalse()
        reads(admission(QueryScope(authenticated = TenantIdFilter("other")))).assert().isFalse()
        reads(admission(QueryScope(declared = SearchFilter("x")))).assert().isFalse()
    }

    @Test
    fun `with require-authenticated-scope on, a point read needs an authenticated tenant scope`() {
        val required = QueryEntryPolicy(requireAuthenticatedScope = true)
        listOf(true, false).forEach { tracing ->
            // No scope, a declared-only scope, and a declared-only scope without a snapshot schema (snapshot profile).
            listOf(
                QueryScope.NONE to true,
                QueryScope(declared = TenantIdFilter("tenant")) to true,
                QueryScope(declared = TenantIdFilter("tenant")) to false,
            ).forEach { (scope, withSchema) ->
                admission(scope, withSchema = withSchema, entryPolicy = required)
                    .read(MOCK_AGGREGATE_METADATA, request, state, tracing).test()
                    .expectErrorSatisfies {
                        it.assert().isInstanceOf(QueryScopeRequiredException::class.java)
                        (it as QueryScopeRequiredException).errorCode.assert()
                            .isEqualTo(ErrorCodes.ILLEGAL_ACCESS_QUERY_SCOPE)
                    }
                    .verify()
            }
            admission(QueryScope(authenticated = TenantIdFilter("tenant")), entryPolicy = required)
                .read(MOCK_AGGREGATE_METADATA, request, state, tracing).blockOptional().isPresent.assert().isTrue()
            // Off, a declared scope is enough.
            admission(QueryScope(declared = TenantIdFilter("tenant")))
                .read(MOCK_AGGREGATE_METADATA, request, state, tracing).blockOptional().isPresent.assert().isTrue()
        }
    }

    @Test
    fun `policies see a single HTTP query by id and restrict in memory`() {
        val contexts = mutableListOf<QueryContext<*>>()
        fun policy(filter: FilterExpression) = QueryPolicy { _, context ->
            contexts += context
            Mono.just(filter)
        }
        reads(admission(policies = listOf(policy(MatchAllFilter), policy(OwnerIdFilter("owner"))))).assert().isTrue()
        reads(admission(policies = listOf(policy(OwnerIdFilter("other"))))).assert().isFalse()
        contexts.map { it.queryType to it.entry }.distinct()
            .assert().containsExactly(QueryType.SINGLE to QueryEntry.HTTP)
        contexts.first().query.toString().assert().contains("a1")

        admission(policies = listOf(policy(MatchAllFilter)), withSchema = false)
            .read(MOCK_AGGREGATE_METADATA, request, state).test()
            .expectErrorMessage("Point-read admission needs the snapshot query schema to evaluate query policies.")
            .verify()
    }

    @Test
    fun `a load hides a deleted state by the snapshot default while tracing reads every version`() {
        val deleted = MOCK_AGGREGATE_METADATA.toStateAggregate(
            state = MockStateAggregate("a1"),
            version = 2,
            tenantId = "tenant",
            deleted = true,
        )
        admission().read(MOCK_AGGREGATE_METADATA, request, deleted).blockOptional().isPresent.assert().isFalse()
        admission().read(MOCK_AGGREGATE_METADATA, request, deleted, tracing = true).blockOptional().isPresent
            .assert().isTrue()
    }

    @Test
    fun `the scope compares numbers by value and lowers eq null`() {
        reads(admission(QueryScope(declared = EqualFilter(QueryField("version"), LongNode.valueOf(1L)))))
            .assert().isTrue()
        reads(admission(QueryScope(declared = EqualFilter(QueryField("state.missing"), NullNode.instance))))
            .assert().isTrue()
    }

    @Test
    fun `load returns the state json when enabled and the state object when off`() {
        admission().state(MOCK_AGGREGATE_METADATA, request, state).block().toString()
            .assert().contains("\"id\":\"a1\"")
        PointReadAdmission.DISABLED.state(MOCK_AGGREGATE_METADATA, request, state).block()
            .assert().isSameAs(state.state)
    }

    @Test
    fun `the tracing cap applies only when enabled and non-zero`() {
        PointReadAdmission(enabled = true, tracingMaxVersions = 2).requireTracingVersions(2)
        assertThrows<IllegalArgumentException> {
            PointReadAdmission(enabled = true, tracingMaxVersions = 2).requireTracingVersions(3)
        }
        PointReadAdmission(enabled = true, tracingMaxVersions = 0).requireTracingVersions(Int.MAX_VALUE)
        PointReadAdmission(tracingMaxVersions = 2).requireTracingVersions(3)
        assertThrows<IllegalArgumentException> { PointReadAdmission(tracingMaxVersions = -1) }
    }
}
