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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.schedule.Misfire
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
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
    fun `the mapper registers the Kotlin module and Wow's transport and enum modules only and ignores unknown properties`() {
        WowJson.mapper.registeredModules().map { it.moduleName }
            .assert().containsExactly(KotlinModule.Builder().build().moduleName, "wow-transport", WowEnums.MODULE_NAME)
        WowJson.mapper.readValue<Price>("""{"amount":1,"exact":1,"unknown":{"a":[1]}}""")
            .assert().isEqualTo(Price(1.0, BigDecimal.ONE))
    }

    @Test
    fun `a ref should be written without its derived isAssigned, and read with or without it`() {
        val ref = AggregateType("shop", "cart").ref("t1", "c-1")
        val written = WowJson.mapper.writeValueAsString(ref)
        written.assert().isEqualTo("""{"type":{"context":"shop","name":"cart"},"tenantId":"t1","id":"c-1"}""")
        WowJson.mapper.writeValueAsString(
            AggregateType("shop", "cart").unassigned("t1")
        ).assert().doesNotContain("isAssigned")
        // An older writer's payload still reads, even where unknown properties fail.
        val older = """{"type":{"context":"shop","name":"cart"},"tenantId":"t1","id":"c-1","isAssigned":true}"""
        WowJson.mapper.readValue<AggregateRef>(older).assert().isEqualTo(ref)
        WowJson.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
            .readValue<AggregateRef>(older).assert().isEqualTo(ref)
    }

    @Test
    fun `a reader older than its writer reads an unknown status, stage or kind as UNKNOWN, and Wow's mapper refuses it`() {
        val newer = """{"format":1,"commandId":"c","requestId":"r","correlationId":"c",
            "ref":{"type":{"context":"shop","name":"cart"},"tenantId":"t","id":"c-1"},
            "hop":{"stage":"delivered","context":"shop"},
            "function":{"kind":"workflow","context":"shop","processor":"p","name":"n"},
            "status":"deferred","version":1,"signalTime":1}"""
        val read = WowJson.readerBuilder().build().readValue<CommandResult>(newer)
        read.status.assert().isEqualTo(CommandResult.Status.UNKNOWN)
        read.hop.stage.assert().isEqualTo(Stage.UNKNOWN)
        read.function.kind.assert().isEqualTo(FunctionKind.UNKNOWN)
        // A known value reads as itself, by its wire name alone.
        WowJson.readerBuilder().build().readValue<Stage>("\"committed\"").assert().isEqualTo(Stage.COMMITTED)
        WowJson.readerBuilder().build().readValue<Stage>("\"COMMITTED\"").assert().isEqualTo(Stage.UNKNOWN)
        assertThrows<JacksonException> { WowJson.mapper.readValue<Stage>("\"delivered\"") }
    }

    /** A domain's own enumeration, which Wow never respells. */
    enum class Colour { DEEP_RED, Green }

    data class Paint(val colour: Colour, val misfire: Misfire, val status: CommandResult.Status)

    @Test
    fun `Wow's enumerations are written and read by their wire names alone, a domain's as its Jackson writes them`() {
        val paint = Paint(Colour.DEEP_RED, Misfire.CATCH_UP, CommandResult.Status.SUCCEEDED)
        val written = WowJson.mapper.writeValueAsString(paint)
        written.assert().isEqualTo("""{"colour":"DEEP_RED","misfire":"catch-up","status":"succeeded"}""")
        WowJson.mapper.readValue<Paint>(written).assert().isEqualTo(paint)
        // The domain's enumeration round-trips exactly as plain Jackson writes it.
        WowJson.mapper.writeValueAsString(Colour.Green).assert()
            .isEqualTo(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Colour.Green))
        // A Wow value spelled otherwise is unknown: refused by a writer's reader, UNKNOWN or refused for an older one.
        assertThrows<JacksonException> { WowJson.mapper.readValue<Misfire>("\"CATCH_UP\"") }
        assertThrows<JacksonException> { WowJson.readerBuilder().build().readValue<Misfire>("\"CATCH_UP\"") }
        WowEnums.TYPES.flatMap { it.enumConstants.toList() }.forEach { value ->
            WowJson.mapper.writeValueAsString(value).assert().matches("\"[a-z]+(-[a-z]+)*\"")
        }
    }
}
