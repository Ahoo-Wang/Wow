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
import me.ahoo.wow.api.query.DeletionScope
import me.ahoo.wow.api.query.LogicalExpression
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.PredicateExpression
import me.ahoo.wow.api.query.PredicateOperator
import me.ahoo.wow.api.query.Query
import me.ahoo.wow.api.query.QueryBudget
import me.ahoo.wow.api.query.QueryCapabilities
import me.ahoo.wow.api.query.QueryErrorCode
import me.ahoo.wow.api.query.QueryException
import me.ahoo.wow.api.query.QueryScope
import me.ahoo.wow.api.query.QueryStage
import me.ahoo.wow.api.query.SearchExpression
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.query.schema.JacksonQuerySchemaProvider
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Instant

class CompositeQueryPolicyTest {
    private val metadata = aggregateMetadata<MockCommandAggregate, MockStateAggregate>()
    private val schema = JacksonQuerySchemaProvider(JsonSerializer).getSchema(metadata)
    private val fieldId = LogicalField("state.id")
    private val fieldData = LogicalField("state.data")

    @Test
    fun `should merge authorization independently of policy order`() {
        val first = policy(fieldId, setOf(fieldId, fieldData), 100)
        val second = policy(fieldData, setOf(fieldData), 50)

        val forward = CompositeQueryPolicy(listOf(first, second)).evaluate(context()).block()!!
        val reverse = CompositeQueryPolicy(listOf(second, first)).evaluate(context()).block()!!

        forward.decision.assert().isEqualTo(reverse.decision)
        forward.fieldAccess.assert().isEqualTo(QueryFieldAccess.Restricted(setOf(fieldData)))
        forward.maximumBudget.maxRecords.assert().isEqualTo(50)
        (forward.mandatoryFilter as LogicalExpression).operands.toSet().assert()
            .isEqualTo((reverse.mandatoryFilter as LogicalExpression).operands.toSet())
    }

    @Test
    fun `should enforce trusted space and deletion scopes`() {
        val policy = CompositeQueryPolicy(listOf(SystemQueryPolicy()))
        val deletionPermission = setOf(QueryPolicyPermissions.QUERY_DELETED_SNAPSHOTS)
        val allowed = QueryAuthority(spaceIds = setOf("space-b", "space-a"), permissions = deletionPermission)

        val multiSpace = policy.evaluate(
            context(Query(scope = QueryScope(deletion = DeletionScope.ALL)), allowed)
        ).block()!!.mandatoryFilter as PredicateExpression
        multiSpace.operator.assert().isEqualTo(PredicateOperator.IN)
        multiSpace.values.map { it.asString() }.assert().isEqualTo(listOf("space-a", "space-b"))

        val selected = policy.evaluate(
            context(Query(scope = QueryScope(spaceId = "space-b", deletion = DeletionScope.ALL)), allowed)
        ).block()!!.mandatoryFilter as PredicateExpression
        selected.operator.assert().isEqualTo(PredicateOperator.EQ)

        policy.evaluate(
            context(Query(scope = QueryScope(spaceId = "space-c")), allowed)
        ).test().expectErrorMatches(::isPolicyDenied).verify()
        policy.evaluate(
            context(Query(scope = QueryScope(deletion = DeletionScope.DELETED)), QueryAuthority())
        ).test().expectErrorMatches(::isPolicyDenied).verify()

        val deleted = policy.evaluate(
            context(
                Query(scope = QueryScope(deletion = DeletionScope.DELETED)),
                QueryAuthority(permissions = deletionPermission)
            )
        ).block()!!.mandatoryFilter as PredicateExpression
        deleted.operator.assert().isEqualTo(PredicateOperator.IS_TRUE)
    }

    @Test
    fun `should enforce requested capabilities and normalize policy failures`() {
        val search = Query(filter = SearchExpression("term", setOf(fieldData)))
        val withoutCapability = CompositeQueryPolicy(
            listOf(QueryPolicy { Mono.just(QueryAuthorization(decision = QueryDecision.GRANT)) })
        )
        withoutCapability.evaluate(context(search)).test().expectErrorMatches(::isPolicyDenied).verify()

        val withCapability = CompositeQueryPolicy(
            listOf(
                QueryPolicy {
                    Mono.just(
                        QueryAuthorization(
                            decision = QueryDecision.GRANT,
                            capabilities = mapOf(QueryCapabilities.FULL_TEXT to CapabilityDecision.GRANT)
                        )
                    )
                }
            )
        )
        withCapability.evaluate(context(search)).test().expectNextCount(1).verifyComplete()

        CompositeQueryPolicy(listOf(QueryPolicy { Mono.empty() }))
            .evaluate(context()).test().expectErrorMatches(::isPolicyFailure).verify()
        CompositeQueryPolicy(listOf(QueryPolicy { Mono.error(IllegalStateException("failure")) }))
            .evaluate(context()).test().expectErrorMatches(::isPolicyFailure).verify()

        val queryError = QueryException(QueryErrorCode.INVALID_QUERY, QueryStage.PREPARATION)
        CompositeQueryPolicy(listOf(QueryPolicy { Mono.error(queryError) }))
            .evaluate(context()).test().expectErrorMatches { it === queryError }.verify()
        assertThrows<IllegalArgumentException> { CompositeQueryPolicy(emptyList()) }
    }

    @Test
    fun `should redact policy context values`() {
        QueryAuthority(subjectId = "subject").toString().assert().isEqualTo("QueryAuthority(<redacted>)")
        QueryAuthorization(decision = QueryDecision.GRANT).toString().assert()
            .isEqualTo("QueryAuthorization(<redacted>)")
        context().toString().assert().isEqualTo(
            "QueryPolicyContext(operation=STREAM, resultKind=RECORD, query=<redacted>, authority=<redacted>)"
        )
    }

    private fun policy(field: LogicalField, access: Set<LogicalField>, maximum: Long): QueryPolicy = QueryPolicy {
        Mono.just(
            QueryAuthorization(
                decision = QueryDecision.GRANT,
                mandatoryFilter = PredicateExpression(
                    field,
                    PredicateOperator.EQ,
                    listOf(JsonNodeFactory.instance.textNode("value"))
                ),
                fieldAccess = QueryFieldAccess.Restricted(access),
                maximumBudget = QueryBudget(maxRecords = maximum)
            )
        )
    }

    private fun context(
        query: Query = Query(),
        authority: QueryAuthority = QueryAuthority.ANONYMOUS
    ): QueryPolicyContext = QueryPolicyContext(
        target = metadata.namedAggregate,
        stateType = MockStateAggregate::class.java,
        operation = QueryOperation.STREAM,
        resultKind = QueryResultKind.RECORD,
        query = query,
        authority = authority,
        schema = schema,
        subscribedAt = Instant.EPOCH,
        deadline = null
    )

    private fun isPolicyDenied(error: Throwable): Boolean =
        error is QueryException && error.code == QueryErrorCode.POLICY_DENIED

    private fun isPolicyFailure(error: Throwable): Boolean =
        error is QueryException && error.code == QueryErrorCode.POLICY_FAILURE
}
