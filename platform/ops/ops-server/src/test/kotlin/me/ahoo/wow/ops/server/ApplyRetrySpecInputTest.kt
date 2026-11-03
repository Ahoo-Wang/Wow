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

package me.ahoo.wow.ops.server

import jakarta.validation.Validation
import me.ahoo.test.asserts.assert
import me.ahoo.wow.ops.api.parked.ApplyRetrySpec
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

/**
 * What [ApplyRetrySpec] takes at admission, over the table the console's form checks against too
 * (`apply-retry-spec-inputs.json`, read by `platform/ops/console`'s tests): the host refuses what its constraints refuse
 * (`wow.command.invalid`) and a body its JSON cannot read as the command (`wow.command.malformed`).
 */
class ApplyRetrySpecInputTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    private val table: JsonNode = javaClass.getResourceAsStream("/apply-retry-spec-inputs.json")!!.use {
        WowJson.mapper.readTree(it)
    }

    private fun texts(kind: String, verdict: String): List<String> = table[kind][verdict].values().map { it.asString() }

    /** Whether the host takes the body: its JSON reads it as the command, and no constraint refuses it. */
    private fun takes(body: String): Boolean {
        val command = runCatching { WowJson.mapper.readValue(body, ApplyRetrySpec::class.java) }.getOrNull()
        return command != null && validator.validate(command).isEmpty()
    }

    @Test
    fun `each duration should be taken or refused as Kotlin's Duration parse reads it, in either member`() {
        for (member in listOf("initialBackoff", "claimTtl")) {
            val body = { text: String -> WowJson.mapper.writeValueAsString(mapOf(member to text)) }
            texts("durations", "taken").filterNot { takes(body(it)) }.assert().describedAs("refused $member").isEmpty()
            texts("durations", "refused").filter { takes(body(it)) }.assert().describedAs("taken $member").isEmpty()
        }
    }

    @Test
    fun `a retry count should be a whole number from 0 to the Int's largest`() {
        val body = { count: String -> """{"maxRetries":$count}""" }
        texts("maxRetries", "taken").filterNot { takes(body(it)) }.assert().isEmpty()
        texts("maxRetries", "refused").filter { takes(body(it)) }.assert().isEmpty()
    }

    @Test
    fun `a refusal should name the member it refuses`() {
        validator.validate(ApplyRetrySpec(maxRetries = -1, initialBackoff = "PT1.5M", claimTtl = "1m 1h"))
            .map { it.propertyPath.toString() }
            .assert().containsExactlyInAnyOrder("maxRetries", "initialBackoff", "claimTtl")
        // A member left out keeps the entry's value: nothing to refuse.
        validator.validate(ApplyRetrySpec()).assert().isEmpty()
    }
}
