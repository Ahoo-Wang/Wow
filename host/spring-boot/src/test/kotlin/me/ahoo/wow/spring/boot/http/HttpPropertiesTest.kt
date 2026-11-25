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

package me.ahoo.wow.spring.boot.http

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.config.WowConfigurationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The HTTP edge's properties: the status of each error code, and the request facts. */
class HttpPropertiesTest {
    @Test
    fun `an error code without a status should refuse the start, naming its key`() {
        val locked = HttpProperties.Error().apply { status = 423 }
        val http = HttpProperties().apply {
            errors["shop.cart.locked"] = locked
            errors["shop.cart.full"] = HttpProperties.Error()
        }
        val refused = assertThrows<WowConfigurationException> { http.errorStatuses() }
        refused.key.assert().isEqualTo("wow.http.errors.[shop.cart.full].status")
        refused.value.assert().isNull()
        HttpProperties().apply { errors["shop.cart.locked"] = locked }.errorStatuses().assert()
            .isEqualTo(mapOf("shop.cart.locked" to 423))
    }

    @Test
    fun `a code under wow that the framework does not answer should refuse the start, naming the nearest codes`() {
        val status = HttpProperties.Error().apply { status = 503 }
        val refused = assertThrows<WowConfigurationException> {
            HttpProperties().apply { errors["wow.command.bussy"] = status }.errorStatuses()
        }
        refused.key.assert().isEqualTo("wow.http.errors.[wow.command.bussy]")
        refused.value.assert().isEqualTo("wow.command.bussy")
        refused.fix.assert().contains("wow.command.busy")
        nearestFrameworkCodes("wow.command.bussy").first().assert().isEqualTo("wow.command.busy")
        nearestFrameworkCodes("wow.query.bussy").first().assert().isEqualTo("wow.query.busy")
        // A framework code takes another status; a code of the application's is the application's to declare.
        HttpProperties().apply {
            errors["wow.command.busy"] = status
            errors["shop.cart.full"] = status
        }.errorStatuses().keys.assert().containsExactly("wow.command.busy", "shop.cart.full")
    }

    @Test
    fun `a code of the application's that no Manifest declares should be told apart, a framework code never`() {
        val status = HttpProperties.Error().apply { status = 409 }
        val http = HttpProperties().apply {
            errors["shop.cart.full"] = status
            errors["shop.cart.fulll"] = status
            errors["wow.command.busy"] = status
        }
        http.undeclaredErrorCodes(setOf("shop.cart.full")).assert().containsExactly("shop.cart.fulll")
    }

    @Test
    fun `a standard request fact listed should refuse the start, in any case, as no header sets it`() {
        listOf("user_agent", "REMOTE_IP", "app_id").forEach { name ->
            val refused = assertThrows<WowConfigurationException> {
                HttpProperties().apply { requestFacts += setOf("X-Device", name) }.requestFactNames()
            }
            refused.key.assert().isEqualTo("wow.http.request-facts")
            refused.value.assert().isEqualTo(name)
            refused.fix.assert().contains("Remove $name", "wow.http.client-facts.enabled")
        }
        HttpProperties().apply { requestFacts += setOf("X-Device", "User-Agent") }.requestFactNames().assert()
            .containsExactly("X-Device", "User-Agent")
    }
}
