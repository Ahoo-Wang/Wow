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

package me.ahoo.wow.query.internal.policy

import me.ahoo.test.asserts.assert
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.internal.model.QueryExecutionMode
import me.ahoo.wow.query.internal.model.QueryValidationMode
import me.ahoo.wow.query.internal.normalization.BackendId
import me.ahoo.wow.query.internal.normalization.LogicalField
import me.ahoo.wow.query.internal.normalization.NormalizedCondition
import me.ahoo.wow.query.internal.normalization.PredicateOperator
import me.ahoo.wow.query.internal.normalization.SystemFieldKind
import me.ahoo.wow.query.internal.normalization.Utf8Json
import me.ahoo.wow.query.internal.planning.FieldAccess
import me.ahoo.wow.query.internal.planning.PlanningFixtures
import me.ahoo.wow.query.internal.planning.QueryFieldConstraint
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejection
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.time.Instant

class QueryPolicyEnforcerTest {
    private val context = QueryExecutionContext(
        target = PlanningFixtures.target,
        purpose = QueryPurpose("interactive-query"),
        authority = QueryAuthority.Subject(
            "subject-1",
            "tenant-1",
            QueryOwnerGrant.Unrestricted,
            QuerySpaceGrant.Unrestricted,
        ),
        executionMode = QueryExecutionMode.PLANNED,
        validationMode = QueryValidationMode.STRICT,
        resourceScope = QueryResourceScope("tenant-1"),
        deadline = Instant.parse("2026-08-07T00:01:00Z"),
        budget = QueryExecutionBudget(maxReturnedRecords = 100),
    )

    @Test
    fun `allow should produce immutable planning constraints with mandatory provenance`() {
        val source = linkedSetOf(PlanningFixtures.name)
        val mandatory = NormalizedCondition.Predicate(
            LogicalField.System(SystemFieldKind.TENANT_ID),
            PredicateOperator.EQ,
            NormalizedValue.Text("tenant-1"),
        )
        val allowance = QueryPolicyAllowance.builder()
            .mandatoryCondition(mandatory)
            .fieldConstraint(
                QueryFieldConstraint(
                    filterFields = FieldAccess.AllowList(source),
                    projectionFields = FieldAccess.AllowList(source),
                ),
            )
            .build()
        source += PlanningFixtures.amount

        QueryPolicyEnforcer(QueryPolicy { Mono.just(QueryPolicyDecision.Allow(allowance)) })
            .authorize(QueryPolicyInput(context, PlanningFixtures.single(), PlanningFixtures.schema))
            .test()
            .consumeNextWith { constraints ->
                constraints.validationMode.assert().isEqualTo(QueryValidationMode.STRICT)
                constraints.mandatoryCondition.assert().isEqualTo(mandatory)
                constraints.fieldConstraint.filterFields.permits(PlanningFixtures.name).assert().isTrue()
                constraints.fieldConstraint.filterFields.permits(PlanningFixtures.amount).assert().isFalse()
            }
            .verifyComplete()
        val equalAllowance = QueryPolicyAllowance.builder()
            .mandatoryCondition(mandatory)
            .fieldConstraint(
                QueryFieldConstraint(
                    filterFields = FieldAccess.AllowList(setOf(PlanningFixtures.name)),
                    projectionFields = FieldAccess.AllowList(setOf(PlanningFixtures.name)),
                ),
            )
            .build()
        allowance.assert().isEqualTo(equalAllowance)
        allowance.hashCode().assert().isEqualTo(equalAllowance.hashCode())
    }

    @Test
    fun `deny empty and error should all fail closed`() {
        assertRejected(QueryRejectionCode.POLICY_DENIED) {
            QueryPolicyEnforcer(QueryPolicy { Mono.just(QueryPolicyDecision.Deny(QueryPolicyDenial.TENANT_MISMATCH)) })
                .authorize(QueryPolicyInput(context, PlanningFixtures.single(), PlanningFixtures.schema))
        }
        QueryPolicyEnforcer(QueryPolicy { Mono.just(QueryPolicyDecision.Deny(QueryPolicyDenial.TENANT_MISMATCH)) })
            .authorize(QueryPolicyInput(context, PlanningFixtures.single(), PlanningFixtures.schema))
            .test()
            .consumeErrorWith { error ->
                (error.cause as QueryPolicyDeniedException).reason.assert()
                    .isEqualTo(QueryPolicyDenial.TENANT_MISMATCH)
            }
            .verify()
        assertRejected(QueryRejectionCode.POLICY_DECISION_MISSING) {
            QueryPolicyEnforcer(QueryPolicy { Mono.empty() })
                .authorize(QueryPolicyInput(context, PlanningFixtures.single(), PlanningFixtures.schema))
        }
        val failure = IllegalStateException("policy store unavailable")
        assertRejected(QueryRejectionCode.POLICY_EVALUATION_FAILED, failure) {
            QueryPolicyEnforcer(QueryPolicy { Mono.error(failure) })
                .authorize(QueryPolicyInput(context, PlanningFixtures.single(), PlanningFixtures.schema))
        }
    }

    @Test
    fun `policy builder should reject mandatory native condition`() {
        assertRejected(
            QueryRejectionCode.MANDATORY_NATIVE_NOT_ALLOWED,
            expectedPath = "$.policy.mandatoryCondition",
        ) {
            QueryPolicyEnforcer(
                QueryPolicy {
                    Mono.fromCallable {
                        QueryPolicyDecision.Allow(
                            QueryPolicyAllowance.builder()
                                .mandatoryCondition(
                                    NormalizedCondition.Native(
                                        BackendId("mongo"),
                                        Utf8Json("{\"tenantId\":\"tenant-1\"}"),
                                    ),
                                )
                                .build(),
                        )
                    }
                },
            ).authorize(QueryPolicyInput(context, PlanningFixtures.single(), PlanningFixtures.schema))
        }
    }

    @Test
    fun `tenant policy should turn trusted scope into mandatory predicates and deny selector mismatch`() {
        val scopedContext = context.copy(
            authority = QueryAuthority.Subject(
                "subject-1",
                "tenant-1",
                ownerGrant = QueryOwnerGrant.Only("owner-1"),
                spaceGrant = QuerySpaceGrant.AllowList(setOf("space-2", "space-1")),
            ),
            resourceScope = QueryResourceScope("tenant-1", "owner-1", "space-1"),
        )
        val input = QueryPolicyInput(scopedContext, PlanningFixtures.single(), PlanningFixtures.schema)

        QueryPolicyEnforcer(TenantIsolationQueryPolicy()).authorize(input)
            .test()
            .consumeNextWith { constraints ->
                val junction = constraints.mandatoryCondition as NormalizedCondition.Junction
                junction.children.assert().hasSize(3)
            }
            .verifyComplete()

        assertRejected(QueryRejectionCode.POLICY_DENIED) {
            QueryPolicyEnforcer(TenantIsolationQueryPolicy()).authorize(
                input.copy(executionContext = scopedContext.copy(resourceScope = QueryResourceScope("tenant-2"))),
            )
        }
        listOf(
            QueryResourceScope("tenant-1", ownerId = "owner-2"),
            QueryResourceScope("tenant-1", spaceId = "space-3"),
        ).forEach { mismatchedScope ->
            assertRejected(QueryRejectionCode.POLICY_DENIED) {
                QueryPolicyEnforcer(TenantIsolationQueryPolicy()).authorize(
                    input.copy(executionContext = scopedContext.copy(resourceScope = mismatchedScope)),
                )
            }
        }
    }

    @Test
    fun `subject grants should remain mandatory when selectors are absent or fail closed`() {
        val scopedContext = context.copy(
            authority = QueryAuthority.Subject(
                "subject-1",
                "tenant-1",
                ownerGrant = QueryOwnerGrant.Only("owner-1"),
                spaceGrant = QuerySpaceGrant.AllowList(setOf("space-2", "space-1")),
            ),
            resourceScope = QueryResourceScope("tenant-1"),
        )
        val input = QueryPolicyInput(scopedContext, PlanningFixtures.single(), PlanningFixtures.schema)

        QueryPolicyEnforcer(TenantIsolationQueryPolicy()).authorize(input).test()
            .consumeNextWith { constraints ->
                val junction = constraints.mandatoryCondition as NormalizedCondition.Junction
                junction.children.assert().hasSize(3)
                val spacePredicate = junction.children.single { child ->
                    child is NormalizedCondition.Predicate &&
                        child.field == LogicalField.System(SystemFieldKind.SPACE_ID)
                } as NormalizedCondition.Predicate
                spacePredicate.operator.assert().isEqualTo(PredicateOperator.IN)
                spacePredicate.value.assert().isEqualTo(
                    NormalizedValue.ListValue(
                        listOf(NormalizedValue.Text("space-1"), NormalizedValue.Text("space-2")),
                    ),
                )
            }
            .verifyComplete()

        assertRejected(QueryRejectionCode.POLICY_DENIED) {
            QueryPolicyEnforcer(TenantIsolationQueryPolicy()).authorize(
                input.copy(
                    executionContext = scopedContext.copy(
                        authority = QueryAuthority.Subject(
                            "subject-1",
                            "tenant-1",
                            ownerGrant = QueryOwnerGrant.Only("owner-1"),
                            spaceGrant = QuerySpaceGrant.DenyAll,
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `tenant service should require an explicit purpose and remain tenant scoped`() {
        val serviceContext = context.copy(
            authority = QueryAuthority.Service(
                "compensation-service",
                "tenant-1",
                setOf(QueryPurpose("compensation-retry")),
            ),
            resourceScope = QueryResourceScope("tenant-1"),
        )
        val input = QueryPolicyInput(serviceContext, PlanningFixtures.single(), PlanningFixtures.schema)

        assertRejected(QueryRejectionCode.POLICY_DENIED) {
            QueryPolicyEnforcer(TenantIsolationQueryPolicy()).authorize(input)
        }
        QueryPolicyEnforcer(TenantIsolationQueryPolicy()).authorize(
            input.copy(executionContext = serviceContext.copy(purpose = QueryPurpose("compensation-retry"))),
        ).test()
            .expectNextCount(1)
            .verifyComplete()
        assertRejected(QueryRejectionCode.POLICY_DENIED) {
            QueryPolicyEnforcer(TenantIsolationQueryPolicy()).authorize(
                input.copy(
                    executionContext = serviceContext.copy(
                        purpose = QueryPurpose("compensation-retry"),
                        resourceScope = QueryResourceScope("tenant-2"),
                    ),
                ),
            )
        }
    }

    @Test
    fun `system and legacy authorities should preserve explicit selectors as mandatory predicates`() {
        val scope = QueryResourceScope("tenant-1", "owner-1", "space-1")
        val legacyGrant = LegacyQueryGrant(
            LegacyQueryCallerId("migration"),
            PlanningFixtures.target,
            QueryPurpose("interactive-query"),
            QueryExecutionMode.LEGACY,
            scope,
        )
        listOf<QueryAuthority>(
            QueryAuthority.System("migration", "query-migration"),
            QueryAuthority.Legacy(legacyGrant),
        ).forEach { authority ->
            QueryPolicyEnforcer(TenantIsolationQueryPolicy()).authorize(
                QueryPolicyInput(
                    context.copy(authority = authority, resourceScope = scope),
                    PlanningFixtures.single(),
                    PlanningFixtures.schema,
                ),
            ).test()
                .consumeNextWith { constraints ->
                    val junction = constraints.mandatoryCondition as NormalizedCondition.Junction
                    junction.children.assert().hasSize(3)
                }
                .verifyComplete()
        }
    }

    @Test
    fun `policy failures should normalize typed errors and reject schema-invalid allow-lists`() {
        val upstream = QueryRejectedException(
            QueryRejection(
                QueryRejectionCategory.INVALID_QUERY,
                QueryRejectionPath.ROOT,
                QueryRejectionCode.INVALID_FIELD,
            ),
        )
        assertRejected(QueryRejectionCode.POLICY_EVALUATION_FAILED, upstream) {
            QueryPolicyEnforcer(QueryPolicy { Mono.error(upstream) })
                .authorize(QueryPolicyInput(context, PlanningFixtures.single(), PlanningFixtures.schema))
        }

        val invalidAllowance = QueryPolicyAllowance.builder()
            .fieldConstraint(
                QueryFieldConstraint(
                    filterFields = FieldAccess.AllowList(
                        setOf(QueryFieldId.Path(listOf("state", "missing"))),
                    ),
                ),
            )
            .build()
        assertRejected(
            QueryRejectionCode.POLICY_CONSTRAINT_INVALID,
            expectedPath = "$.policy.fieldConstraint.filterFields",
        ) {
            QueryPolicyEnforcer(QueryPolicy { Mono.just(QueryPolicyDecision.Allow(invalidAllowance)) })
                .authorize(QueryPolicyInput(context, PlanningFixtures.single(), PlanningFixtures.schema))
        }
    }

    private fun assertRejected(
        code: QueryRejectionCode,
        cause: Throwable? = null,
        expectedPath: String = "$.policy",
        publisher: () -> Mono<*>,
    ) {
        publisher().test()
            .consumeErrorWith { error ->
                (error as QueryRejectedException).rejection.category.assert()
                    .isEqualTo(QueryRejectionCategory.ACCESS_DENIED)
                error.rejection.code.assert().isEqualTo(code)
                error.rejection.path.toString().assert().isEqualTo(expectedPath)
                if (cause != null) {
                    error.cause.assert().isSameAs(cause)
                }
            }
            .verify()
    }
}
