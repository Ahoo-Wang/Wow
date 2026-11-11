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

package me.ahoo.wow.spring.boot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.config.WowConfigurationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The HTTP edge's properties: the status of each error code. */
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
}
