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

package me.ahoo.wow.http

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.wire.WowHeaders
import org.junit.jupiter.api.Test

/** The options are a plain class (decision D216) that keeps a data class's equality, hash and string. */
class WowHttpOptionsTest {
    @Test
    fun `an option equals another with the same fields, and prints them`() {
        valueSemantics(WowHttpOptions(), WowHttpOptions(), WowHttpOptions(manifestEndpoint = false))
        WowHttpOptions().copyWith(openApiEndpoint = false).assert().isEqualTo(WowHttpOptions(openApiEndpoint = false))
        WowHttpOptions().copyWith(clientFacts = false).clientFacts.assert().isFalse()
        (WowHttpOptions() == WowHttpOptions(clientFacts = false)).assert().isFalse()
    }

    @Test
    fun `request facts cannot take a reserved name`() {
        assertThrownBy<IllegalArgumentException> { WowHttpOptions(requestFacts = setOf("Wow-Tenant")) }
        assertThrownBy<IllegalArgumentException> { WowHttpOptions(requestFacts = setOf("wow.tenant")) }
        assertThrownBy<IllegalArgumentException> { WowHttpOptions(requestFacts = setOf(" ")) }
        assertThrownBy<IllegalArgumentException> { WowHttpOptions(errorStatuses = mapOf("x" to 200)) }
        assertThrownBy<IllegalArgumentException> { WowHttpOptions(errorStatuses = mapOf("x" to 600)) }
        WowHttpOptions(requestFacts = setOf("X-App-Id"), errorStatuses = mapOf("x" to 400, "y" to 599))
            .requestFacts.assert().containsExactly("X-App-Id")
    }

    @Test
    fun `a command's request id is read from Wow-Request-Id first`() {
        WowHttpOptions().requestIdHeaders.assert().containsExactly(WowHeaders.REQUEST_ID)
        val added = WowHttpOptions().copyWith(requestIdHeaders = listOf(WowHeaders.REQUEST_ID, "X-Request-Id"))
        added.requestIdHeaders.assert().containsExactly(WowHeaders.REQUEST_ID, "X-Request-Id")
        (added == WowHttpOptions()).assert().isFalse()
        assertThrownBy<IllegalArgumentException> { WowHttpOptions(requestIdHeaders = listOf("X-Request-Id")) }
        assertThrownBy<IllegalArgumentException> { WowHttpOptions(requestIdHeaders = emptyList()) }
    }

    private fun valueSemantics(value: Any, same: Any, other: Any) {
        (value == value).assert().isTrue()
        (value == same).assert().isTrue()
        value.hashCode().assert().isEqualTo(same.hashCode())
        (value == other).assert().isFalse()
        (value == value.javaClass.simpleName).assert().isFalse()
        value.toString().assert().startsWith("${value.javaClass.simpleName}(").isEqualTo(same.toString())
    }
}
