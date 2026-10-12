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

package me.ahoo.wow.spi.json

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import java.math.BigDecimal
import java.math.BigInteger

class WowJsonTest {
    data class Price(val amount: Double, val exact: BigDecimal, val extra: Map<String, Any?> = emptyMap())

    private val text = """{"price":2.50,"precise":1234567890.12345678901234567890,"large":123456789012345678901234567890,""" +
        """"long":9007199254740993,"small":7,"nested":{"rate":0.10,"list":[1.0,2]}}"""

    @Test
    fun `untyped values keep every decimal exact`() {
        val value = WowJson.mapper.readValue<Map<String, Any?>>(text)
        value["price"].assert().isEqualTo(BigDecimal("2.50"))
        value["precise"].assert().isEqualTo(BigDecimal("1234567890.12345678901234567890"))
        value["large"].assert().isEqualTo(BigInteger("123456789012345678901234567890"))
        value["long"].assert().isEqualTo(9007199254740993L)
        value["small"].assert().isEqualTo(7)
        @Suppress("UNCHECKED_CAST")
        val nested = value["nested"] as Map<String, Any?>
        nested["rate"].assert().isEqualTo(BigDecimal("0.10"))
        nested["list"].assert().isEqualTo(listOf(BigDecimal("1.0"), 2))
        WowJson.mapper.writeValueAsString(value).assert().isEqualTo(text)
    }

    @Test
    fun `a tree keeps every number as written`() {
        val tree = WowJson.mapper.readTree(text)
        tree["price"].decimalValue().assert().isEqualTo(BigDecimal("2.50"))
        tree["large"].bigIntegerValue().assert().isEqualTo(BigInteger("123456789012345678901234567890"))
        WowJson.mapper.writeValueAsString(tree).assert().isEqualTo(text)
    }

    @Test
    fun `typed properties read as their type says`() {
        val price = WowJson.mapper.readValue<Price>("""{"amount":2.50,"exact":2.50,"extra":{"fee":0.30}}""")
        price.assert().isEqualTo(Price(2.5, BigDecimal("2.50"), mapOf("fee" to BigDecimal("0.30"))))
    }

    @Test
    fun `the mapper registers the Kotlin module only and ignores unknown properties`() {
        WowJson.mapper.registeredModules().map { it.javaClass.name }
            .assert().containsExactly(KotlinModule::class.java.name)
        WowJson.mapper.readValue<Price>("""{"amount":1,"exact":1,"unknown":{"a":[1]}}""")
            .assert().isEqualTo(Price(1.0, BigDecimal.ONE))
    }
}
