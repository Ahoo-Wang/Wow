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

package me.ahoo.wow.identity

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * Where a value for an [identity fact][IdentityFact] came from. The declaration order is the precedence: when several
 * sources give a value, the earliest wins.
 */
@InternalWowApi
enum class IdentitySource {
    /** The command body: an `@TenantId`, `@OwnerId` or `@AggregateId` property, or the command's static tenant. */
    BODY,

    /** The route: the aggregate's static tenant, or a path variable the route declares. */
    ROUTE,

    /** The authenticated principal. */
    AUTH,

    /**
     * Declared by the sender outside the body and not vouched for: an HTTP header, or a value an in-process caller
     * passes to the command builder or to `toCommandMessage`.
     */
    HEADER,

    /** The domain event a saga reacts to. */
    UPSTREAM;

    /** Whether a value from this source fixes the fact, so that no other source may contradict it (V3). */
    val authoritative: Boolean
        get() = this == ROUTE || this == AUTH
}

/** The identity facts of a command or request, each decided by one rule in [IdentityResolver]. */
@InternalWowApi
enum class IdentityFact(val variable: String, val conflictChecked: Boolean) {
    TENANT_ID("tenantId", true),
    OWNER_ID("ownerId", true),
    SPACE_ID("spaceId", true),
    AGGREGATE_ID("aggregateId", false),
    OPERATOR("operator", false),
}

/** A value for an identity fact and the source it came from. */
@InternalWowApi
data class IdentityHint(val source: IdentitySource, val value: String) {
    @InternalWowApi
    companion object {
        fun of(source: IdentitySource, value: String?): IdentityHint? = value?.let { IdentityHint(source, it) }
    }
}

/**
 * The one place that decides the tenant, owner, space, aggregate ID and operator of a command or request, from hints
 * tagged with their [source][IdentitySource]. The HTTP adapter, the command factory (in-process `CommandGateway`
 * callers) and saga-sent commands all resolve through it.
 *
 * The rule, per fact:
 * - The value is the first hint by source precedence: [BODY][IdentitySource.BODY], [ROUTE][IdentitySource.ROUTE],
 *   [AUTH][IdentitySource.AUTH], [HEADER][IdentitySource.HEADER], [UPSTREAM][IdentitySource.UPSTREAM]. A caller
 *   passes only the hints its entry has, normalized as that entry always has (an HTTP header that is blank is no hint).
 * - Since 9.3.0 (V3): for the tenant, owner and space, when the route or the authenticated principal fixes the value,
 *   a body or header hint with a different value is rejected with an [IllegalArgumentException] (`IllegalArgument`,
 *   400) instead of silently winning or being ignored. The same value is fine.
 */
@InternalWowApi
object IdentityResolver {
    fun resolve(fact: IdentityFact, vararg hints: IdentityHint?): String? = resolve(fact, hints.asList())

    fun resolve(fact: IdentityFact, hints: List<IdentityHint?>): String? {
        var winner: IdentityHint? = null
        var fixed: IdentityHint? = null
        for (hint in hints) {
            if (hint == null) {
                continue
            }
            if (winner == null || hint.source < winner.source) {
                winner = hint
            }
            if (hint.source.authoritative && (fixed == null || hint.source < fixed.source)) {
                fixed = hint
            }
        }
        if (fixed != null && fact.conflictChecked) {
            hints.firstOrNull { it != null && !it.source.authoritative && it.value != fixed.value }?.let {
                throw conflict(fact, fixed.source, fixed.value, it.source, it.value)
            }
        }
        return winner?.value
    }

    /**
     * [resolve] for up to three hints, each given as its source and value (a `null` value is no hint), in that order.
     * The same rule without allocating: the per-request paths (HTTP identity, `toCommandMessage`) use it.
     */
    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    fun resolve(
        fact: IdentityFact,
        firstSource: IdentitySource,
        first: String?,
        secondSource: IdentitySource = IdentitySource.UPSTREAM,
        second: String? = null,
        thirdSource: IdentitySource = IdentitySource.UPSTREAM,
        third: String? = null,
    ): String? {
        var winnerSource: IdentitySource? = null
        var winner: String? = null
        var fixedSource: IdentitySource? = null
        var fixed: String? = null
        if (first != null) {
            winnerSource = firstSource
            winner = first
            if (firstSource.authoritative) {
                fixedSource = firstSource
                fixed = first
            }
        }
        if (second != null) {
            if (winnerSource == null || secondSource.ordinal < winnerSource.ordinal) {
                winnerSource = secondSource
                winner = second
            }
            if (secondSource.authoritative && (fixedSource == null || secondSource.ordinal < fixedSource.ordinal)) {
                fixedSource = secondSource
                fixed = second
            }
        }
        if (third != null) {
            if (winnerSource == null || thirdSource.ordinal < winnerSource.ordinal) {
                winner = third
            }
            if (thirdSource.authoritative && (fixedSource == null || thirdSource.ordinal < fixedSource.ordinal)) {
                fixedSource = thirdSource
                fixed = third
            }
        }
        if (fixedSource != null && fact.conflictChecked) {
            checkNotConflicting(fact, fixedSource, fixed!!, firstSource, first)
            checkNotConflicting(fact, fixedSource, fixed, secondSource, second)
            checkNotConflicting(fact, fixedSource, fixed, thirdSource, third)
        }
        return winner
    }

    private fun checkNotConflicting(
        fact: IdentityFact,
        fixedSource: IdentitySource,
        fixed: String,
        source: IdentitySource,
        value: String?
    ) {
        if (value != null && !source.authoritative && value != fixed) {
            throw conflict(fact, fixedSource, fixed, source, value)
        }
    }

    private fun conflict(
        fact: IdentityFact,
        fixedSource: IdentitySource,
        fixed: String,
        source: IdentitySource,
        value: String
    ): IllegalArgumentException = IllegalArgumentException(
        "Conflicting ${fact.variable}: the ${fixedSource.describe()} fixes [$fixed], " +
            "but the ${source.describe()} gives [$value]."
    )

    /**
     * The aggregate ID and owner of a command to an aggregate whose owner may be its ID.
     *
     * When [ownerIsAggregateId], a non-blank [ownerId] is also the aggregate ID; otherwise the aggregate ID is the
     * first of [aggregateId] and the [generated][generateAggregateId] one, and a blank owner becomes that ID. For any
     * other aggregate the two are independent.
     *
     * @param ownerIsAggregateId whether the aggregate's owner is its ID (`OwnerPolicy.AGGREGATE_ID` on an HTTP route).
     * @param ownerId the resolved owner, see [resolve] with [IdentityFact.OWNER_ID].
     * @param aggregateId the resolved aggregate ID, see [resolve] with [IdentityFact.AGGREGATE_ID].
     * @param generateAggregateId generates an aggregate ID when no source gave one.
     * @return the aggregate ID and the owner (`null` when no source gave one).
     */
    fun resolveAggregateIdAndOwner(
        ownerIsAggregateId: Boolean,
        ownerId: String?,
        aggregateId: String?,
        generateAggregateId: () -> String,
    ): Pair<String, String?> {
        val resolvedAggregateId = resolveAggregateId(ownerIsAggregateId, ownerId, aggregateId, generateAggregateId)
        return resolvedAggregateId to resolveOwnerId(ownerIsAggregateId, ownerId, resolvedAggregateId)
    }

    /** The aggregate ID of [resolveAggregateIdAndOwner], without the pair. */
    inline fun resolveAggregateId(
        ownerIsAggregateId: Boolean,
        ownerId: String?,
        aggregateId: String?,
        generateAggregateId: () -> String,
    ): String {
        if (ownerIsAggregateId && !ownerId.isNullOrBlank()) {
            return ownerId
        }
        return aggregateId ?: generateAggregateId()
    }

    /** The owner of [resolveAggregateIdAndOwner], given the aggregate ID [resolveAggregateId] returned. */
    fun resolveOwnerId(ownerIsAggregateId: Boolean, ownerId: String?, resolvedAggregateId: String): String? =
        if (ownerIsAggregateId) resolvedAggregateId else ownerId

    private fun IdentitySource.describe(): String = when (this) {
        IdentitySource.BODY -> "command body"
        IdentitySource.ROUTE -> "route"
        IdentitySource.AUTH -> "authenticated principal"
        IdentitySource.HEADER -> "request header"
        IdentitySource.UPSTREAM -> "upstream event"
    }
}
