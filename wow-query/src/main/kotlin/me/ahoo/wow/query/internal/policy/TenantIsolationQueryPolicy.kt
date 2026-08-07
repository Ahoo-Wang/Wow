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

import me.ahoo.wow.query.internal.normalization.JunctionOperator
import me.ahoo.wow.query.internal.normalization.LogicalField
import me.ahoo.wow.query.internal.normalization.NormalizedCondition
import me.ahoo.wow.query.internal.normalization.NormalizedValue
import me.ahoo.wow.query.internal.normalization.PredicateOperator
import me.ahoo.wow.query.internal.normalization.SystemFieldKind
import me.ahoo.wow.query.internal.planning.NativeBackendAccess
import me.ahoo.wow.query.internal.planning.QueryFieldConstraint
import reactor.core.publisher.Mono

/**
 * Minimal target-independent tenant boundary used by the future Gateway wiring.
 *
 * Route/header values are selectors only. A subject or tenant-scoped service must agree with them before the selector
 * becomes a mandatory condition. System and exactly granted legacy authorities remain separate, auditable
 * authority variants instead of being inferred from a missing authentication context.
 */
internal class TenantIsolationQueryPolicy : QueryPolicy {
    override fun decide(input: QueryPolicyInput): Mono<QueryPolicyDecision> = Mono.fromSupplier {
        when (val authority = input.executionContext.authority) {
            is QueryAuthority.Subject -> decideSubject(authority, input.executionContext.resourceScope)
            is QueryAuthority.Service -> decideService(
                authority,
                input.executionContext.purpose,
                input.executionContext.resourceScope,
            )

            is QueryAuthority.System,
            is QueryAuthority.Legacy,
            -> input.executionContext.resourceScope.let { scope ->
                allow(mandatoryScope(scope.tenantId, scope.ownerId, scope.spaceId?.let(::spaceEquals)))
            }
        }
    }

    private fun decideSubject(
        authority: QueryAuthority.Subject,
        scope: QueryResourceScope,
    ): QueryPolicyDecision {
        if (scope.tenantId != null && scope.tenantId != authority.tenantId) {
            return QueryPolicyDecision.Deny(QueryPolicyDenial.TENANT_MISMATCH)
        }
        val ownerId =
            when (val owner = resolveOwner(authority.ownerGrant, scope.ownerId)) {
                ScopeResolution.Denied -> return QueryPolicyDecision.Deny(QueryPolicyDenial.OWNER_MISMATCH)
                ScopeResolution.Unrestricted -> null
                is ScopeResolution.Restricted -> owner.value
            }
        val spaceCondition =
            when (val space = resolveSpace(authority.spaceGrant, scope.spaceId)) {
                ScopeResolution.Denied -> return QueryPolicyDecision.Deny(QueryPolicyDenial.SPACE_MISMATCH)
                ScopeResolution.Unrestricted -> null
                is ScopeResolution.Restricted -> space.value
            }
        return allow(
            mandatoryScope(
                authority.tenantId,
                ownerId,
                spaceCondition,
            ),
        )
    }

    private fun decideService(
        authority: QueryAuthority.Service,
        purpose: QueryPurpose,
        scope: QueryResourceScope,
    ): QueryPolicyDecision {
        if (purpose !in authority.purposes) {
            return QueryPolicyDecision.Deny(QueryPolicyDenial.PURPOSE_NOT_ALLOWED)
        }
        if (scope.tenantId != null && scope.tenantId != authority.tenantId) {
            return QueryPolicyDecision.Deny(QueryPolicyDenial.TENANT_MISMATCH)
        }
        return allow(mandatoryScope(authority.tenantId, scope.ownerId, scope.spaceId?.let(::spaceEquals)))
    }

    private fun allow(mandatoryCondition: NormalizedCondition): QueryPolicyDecision.Allow =
        QueryPolicyDecision.Allow(
            QueryPolicyAllowance.builder()
                .mandatoryCondition(mandatoryCondition)
                .fieldConstraint(QueryFieldConstraint(nativeBackends = NativeBackendAccess.DenyAll))
                .build(),
        )

    private fun mandatoryScope(
        tenantId: String?,
        ownerId: String?,
        spaceCondition: NormalizedCondition?,
    ): NormalizedCondition {
        val predicates = buildList<NormalizedCondition> {
            tenantId?.let { add(systemPredicate(SystemFieldKind.TENANT_ID, it)) }
            ownerId?.let { add(systemPredicate(SystemFieldKind.OWNER_ID, it)) }
            spaceCondition?.let(::add)
        }
        return when (predicates.size) {
            0 -> NormalizedCondition.All
            1 -> predicates.single()
            else -> NormalizedCondition.Junction(
                JunctionOperator.AND,
                predicates,
            )
        }
    }

    private fun systemPredicate(kind: SystemFieldKind, value: String): NormalizedCondition.Predicate =
        NormalizedCondition.Predicate(
            LogicalField.System(kind),
            PredicateOperator.EQ,
            NormalizedValue.Text(value),
        )

    private fun resolveOwner(grant: QueryOwnerGrant, selector: String?): ScopeResolution<String> =
        when (grant) {
            QueryOwnerGrant.Unrestricted -> selector?.let { ScopeResolution.Restricted(it) }
                ?: ScopeResolution.Unrestricted

            is QueryOwnerGrant.Only -> {
                if (selector == null || selector == grant.ownerId) {
                    ScopeResolution.Restricted(grant.ownerId)
                } else {
                    ScopeResolution.Denied
                }
            }
        }

    private fun resolveSpace(
        grant: QuerySpaceGrant,
        selector: String?,
    ): ScopeResolution<NormalizedCondition> =
        when (grant) {
            QuerySpaceGrant.Unrestricted -> selector?.let { ScopeResolution.Restricted(spaceEquals(it)) }
                ?: ScopeResolution.Unrestricted

            QuerySpaceGrant.DenyAll -> ScopeResolution.Denied
            is QuerySpaceGrant.AllowList -> {
                if (selector != null) {
                    if (selector in grant.spaceIds) {
                        ScopeResolution.Restricted(spaceEquals(selector))
                    } else {
                        ScopeResolution.Denied
                    }
                } else {
                    ScopeResolution.Restricted(
                        NormalizedCondition.Predicate(
                            LogicalField.System(SystemFieldKind.SPACE_ID),
                            PredicateOperator.IN,
                            NormalizedValue.ListValue(grant.spaceIds.map(NormalizedValue::Text)),
                        ),
                    )
                }
            }
        }

    private fun spaceEquals(spaceId: String): NormalizedCondition =
        systemPredicate(SystemFieldKind.SPACE_ID, spaceId)

    private sealed interface ScopeResolution<out T> {
        data object Denied : ScopeResolution<Nothing>

        data object Unrestricted : ScopeResolution<Nothing>

        data class Restricted<T>(val value: T) : ScopeResolution<T>
    }
}
