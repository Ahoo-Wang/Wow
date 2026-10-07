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

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.identity.IdentitySource.AUTH
import me.ahoo.wow.identity.IdentitySource.BODY
import me.ahoo.wow.identity.IdentitySource.HEADER
import me.ahoo.wow.identity.IdentitySource.ROUTE
import me.ahoo.wow.identity.IdentitySource.UPSTREAM
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class IdentityResolverTest {

    private fun hint(source: IdentitySource, value: String?) = IdentityHint.of(source, value)

    @Test
    fun `the value is the first hint by source precedence`() {
        IdentityResolver.resolve(
            IdentityFact.TENANT_ID,
            hint(UPSTREAM, "upstream"),
            hint(HEADER, "header"),
        ).assert().isEqualTo("header")
        IdentityResolver.resolve(IdentityFact.TENANT_ID, hint(HEADER, "header"), hint(BODY, "body"))
            .assert().isEqualTo("body")
        IdentityResolver.resolve(IdentityFact.SPACE_ID, hint(UPSTREAM, "upstream"), hint(HEADER, null))
            .assert().isEqualTo("upstream")
        IdentityResolver.resolve(IdentityFact.OPERATOR, hint(HEADER, "header"), hint(AUTH, "principal"))
            .assert().isEqualTo("principal")
        IdentityResolver.resolve(IdentityFact.OWNER_ID, hint(HEADER, null), null).assert().isNull()
    }

    /** Null is no hint; a blank value is a value, as `?:` always treated it. */
    @Test
    fun `a blank value is a value`() {
        IdentityResolver.resolve(IdentityFact.TENANT_ID, hint(BODY, ""), hint(HEADER, "header"))
            .assert().isEqualTo("")
    }

    @ParameterizedTest
    @EnumSource(names = ["TENANT_ID", "OWNER_ID", "SPACE_ID"])
    fun `V3 - a body or header contradicting what the route fixes is rejected`(fact: IdentityFact) {
        assertThrownBy<IllegalArgumentException> {
            IdentityResolver.resolve(fact, hint(ROUTE, "route"), hint(BODY, "body"))
        }.hasMessage(
            "Conflicting ${fact.variable}: the route fixes [route], but the command body gives [body]."
        )
        assertThrownBy<IllegalArgumentException> {
            IdentityResolver.resolve(fact, hint(HEADER, "header"), hint(ROUTE, "route"))
        }.hasMessage(
            "Conflicting ${fact.variable}: the route fixes [route], but the request header gives [header]."
        )
        assertThrownBy<IllegalArgumentException> {
            IdentityResolver.resolve(fact, hint(AUTH, "principal"), hint(HEADER, "header"))
        }.hasMessage(
            "Conflicting ${fact.variable}: the authenticated principal fixes [principal], " +
                "but the request header gives [header]."
        )
    }

    @ParameterizedTest
    @EnumSource(names = ["TENANT_ID", "OWNER_ID", "SPACE_ID"])
    fun `V3 - the same value from the body or a header is fine`(fact: IdentityFact) {
        IdentityResolver.resolve(fact, hint(ROUTE, "route"), hint(BODY, "route"), hint(HEADER, "route"))
            .assert().isEqualTo("route")
    }

    /** Without a route or principal fixing the fact, the body still wins over a header, as it always has. */
    @ParameterizedTest
    @EnumSource(names = ["TENANT_ID", "OWNER_ID", "SPACE_ID"])
    fun `without a fixed value a body and a header may differ`(fact: IdentityFact) {
        IdentityResolver.resolve(fact, hint(HEADER, "header"), hint(BODY, "body")).assert().isEqualTo("body")
    }

    @ParameterizedTest
    @EnumSource(names = ["AGGREGATE_ID", "OPERATOR"])
    fun `the aggregate ID and the operator are not conflict checked`(fact: IdentityFact) {
        IdentityResolver.resolve(fact, hint(ROUTE, "route"), hint(HEADER, "header"))
            .assert().isEqualTo("route")
        IdentityResolver.resolve(fact, hint(AUTH, "principal"), hint(HEADER, "header"))
            .assert().isEqualTo("principal")
    }

    /**
     * The allocation-free overload the per-request paths use decides exactly as the list form: the same value, or the
     * same rejection, for every fact and every combination of up to three sources and values.
     */
    @Test
    fun `the source and value overload decides as the hint list does`() {
        val values = listOf(null, "a", "b")
        var checked = 0
        for (fact in IdentityFact.entries) {
            for (firstSource in IdentitySource.entries) {
                for (secondSource in IdentitySource.entries) {
                    for (thirdSource in IdentitySource.entries) {
                        for (first in values) {
                            for (second in values) {
                                for (third in values) {
                                    val expected = runCatching {
                                        IdentityResolver.resolve(
                                            fact,
                                            hint(firstSource, first),
                                            hint(secondSource, second),
                                            hint(thirdSource, third),
                                        )
                                    }
                                    val actual = runCatching {
                                        IdentityResolver.resolve(
                                            fact,
                                            firstSource,
                                            first,
                                            secondSource,
                                            second,
                                            thirdSource,
                                            third,
                                        )
                                    }
                                    val case = "$fact $firstSource=$first $secondSource=$second $thirdSource=$third"
                                    actual.getOrNull().assert().describedAs(case).isEqualTo(expected.getOrNull())
                                    actual.exceptionOrNull()?.message.assert().describedAs(case)
                                        .isEqualTo(expected.exceptionOrNull()?.message)
                                    checked++
                                }
                            }
                        }
                    }
                }
            }
        }
        checked.assert().isEqualTo(IdentityFact.entries.size * 125 * 27)
    }

    @Test
    fun `the source and value overload takes one or two hints`() {
        IdentityResolver.resolve(IdentityFact.OPERATOR, AUTH, "principal").assert().isEqualTo("principal")
        IdentityResolver.resolve(IdentityFact.OPERATOR, AUTH, null).assert().isNull()
        IdentityResolver.resolve(IdentityFact.TENANT_ID, BODY, null, HEADER, "header").assert().isEqualTo("header")
    }

    /**
     * The owner = aggregate ID linkage, against the expression `toCommandMessage` used before the resolver, for every
     * combination of its inputs.
     */
    @Test
    fun `the aggregate ID and owner linkage is unchanged`() {
        val owners = listOf(null, "", " ", "owner")
        val aggregateIds = listOf(null, "", "aggregate")
        for (ownerIsAggregateId in listOf(true, false)) {
            for (ownerId in owners) {
                for (aggregateId in aggregateIds) {
                    val actual = IdentityResolver.resolveAggregateIdAndOwner(
                        ownerIsAggregateId = ownerIsAggregateId,
                        ownerId = ownerId,
                        aggregateId = aggregateId,
                        generateAggregateId = { "generated" },
                    )
                    actual.assert()
                        .describedAs("ownerIsAggregateId=$ownerIsAggregateId ownerId=$ownerId aggregateId=$aggregateId")
                        .isEqualTo(legacyAggregateIdAndOwner(ownerIsAggregateId, ownerId, aggregateId))
                }
            }
        }
    }

    /** `toCommandMessage` as of 9.2.3, with the body and argument already merged into [ownerId] / [aggregateId]. */
    private fun legacyAggregateIdAndOwner(
        ownerIdSameAsAggregateId: Boolean,
        commandOwnerId: String?,
        aggregateId: String?,
    ): Pair<String, String?> {
        val commandAggregateId =
            if (ownerIdSameAsAggregateId && commandOwnerId.isNullOrBlank().not()) {
                commandOwnerId!!
            } else {
                aggregateId ?: "generated"
            }
        val finalOwnerId =
            if (ownerIdSameAsAggregateId && commandOwnerId.isNullOrBlank()) {
                commandAggregateId
            } else {
                commandOwnerId
            }
        return commandAggregateId to finalOwnerId
    }
}
