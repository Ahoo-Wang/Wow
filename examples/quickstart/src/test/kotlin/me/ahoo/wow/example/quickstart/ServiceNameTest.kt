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

package me.ahoo.wow.example.quickstart

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.test.server.wowServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The bank's one name is its build's, `bank-service` (`wow { service }`), which the plugin writes into its jar: run
 * under another, the service does not start, though a single-module service has no api contract to compare with.
 */
class ServiceNameTest {
    @Test
    fun `the bank run under another name than its build's should not start, naming the key to set`() {
        val failure = assertThrows<Exception> {
            wowServer<BankServer>("dev", properties = mapOf("spring.application.name" to "bank")).close()
        }
        val refused = generateSequence<Throwable>(failure) { it.cause }
            .filterIsInstance<WowConfigurationException>().first()
        refused.key.assert().isEqualTo("spring.application.name")
        refused.reason.assert()
            .startsWith("The service runs as bank (spring.application.name), but its build names it bank-service (")
            .contains("/META-INF/wow/service.json")
        refused.fix.assert()
            .isEqualTo(
                "Set spring.application.name: bank-service, or name the service bank in its build (wow { service }).",
            )
    }
}
