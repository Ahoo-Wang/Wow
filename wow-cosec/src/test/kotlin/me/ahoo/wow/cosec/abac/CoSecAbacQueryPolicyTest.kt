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

package me.ahoo.wow.cosec.abac

import io.mockk.mockk
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.webflux.ReactiveSecurityContexts
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.abac.AbacTags
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.snapshot.filter.AbacQueryOptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.util.context.Context

class CoSecAbacQueryPolicyTest {
    private val policy = CoSecAbacQueryPolicy()

    private fun tagsOf(principal: CoSecPrincipal?, policy: CoSecAbacQueryPolicy = this.policy): AbacTags {
        val context = principal?.let {
            with(ReactiveSecurityContexts) { Context.empty().setSecurityContext(SimpleSecurityContext(it)) }
        } ?: Context.empty()
        return policy.getPrincipalTags(context, mockk<QueryContext<*>>()).block()!!
    }

    private fun principal(attributes: Map<String, Any>) = SimplePrincipal("user", attributes = attributes)

    @Test
    fun `reads the tags from the principal's prefixed attributes`() {
        tagsOf(
            principal(
                mapOf(
                    "abac.region" to "east, north,,east",
                    "abac.dept" to listOf("eng", " pm ", ""),
                    "abac.level" to arrayOf("L1"),
                    "abac.tier" to 3,
                    "abac." to "nameless",
                    "abac.empty" to " , ",
                    "nickname" to "Ahoo",
                ),
            ),
        ).assert().isEqualTo(
            mapOf(
                "region" to listOf("east", "north"),
                "dept" to listOf("eng", "pm"),
                "level" to listOf("L1"),
                "tier" to listOf("3"),
            ),
        )
    }

    @Test
    fun `an anonymous or missing principal has no tags`() {
        tagsOf(SimplePrincipal.ANONYMOUS).assert().isEmpty()
        tagsOf(null).assert().isEmpty()
    }

    @Test
    fun `the prefix is configurable and never blank`() {
        val custom = CoSecAbacQueryPolicy(AbacQueryOptions(requirePrincipalTags = true), "tag:")
        tagsOf(principal(mapOf("tag:region" to "east", "abac.dept" to "eng")), custom)
            .assert().isEqualTo(mapOf("region" to listOf("east")))
        custom.options.requirePrincipalTags.assert().isTrue()
        assertThrows<IllegalArgumentException> { CoSecAbacQueryPolicy(attributePrefix = " ") }
    }
}
