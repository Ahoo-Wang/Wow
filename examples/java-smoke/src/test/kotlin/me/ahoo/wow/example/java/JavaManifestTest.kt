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

package me.ahoo.wow.example.java

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** The Manifest fragment the compiler wrote for the Java domain, completed with the schemas of its Java types. */
class JavaManifestTest {
    private val context: JsonNode = javaClass.classLoader.getResourceAsStream("META-INF/wow/manifest/java-smoke.json")
        .let { requireNotNull(it) { "The fragment of java-smoke is not on the class path." } }
        .use { JsonMapper.builder().build().readTree(it) }
        .path("contexts").single()

    @Test
    fun `the Java aggregate should be in the context with its commands and events`() {
        context.path("name").asString().assert().isEqualTo("smoke")
        val account = context.path("aggregates").single { it.path("name").asString() == "account" }
        account.path("commands").toList().map { it.path("name").asString() }.assert()
            .contains("OpenAccount", "Deposit", "Withdraw")
        account.path("events").toList().map { it.path("name").asString() }.assert()
            .contains("AccountOpened", "Deposited", "Withdrawn")
    }

    @Test
    fun `the error codes of Java fields should be the context's errors`() {
        context.path("errors").toList().associate { it.path("code").asString() to it.path("status").asInt() }.assert()
            .containsEntry(AccountRejections.INVALID_AMOUNT, 422)
            .containsEntry(AccountRejections.INSUFFICIENT_BALANCE, 409)
    }
}
