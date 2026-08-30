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

package me.ahoo.wow.compensation.server

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.FileSystemResource

class CompensationServerConfigurationTest {
    @Test
    fun `should raise query filter node limit`() {
        val propertySource = YamlPropertySourceLoader()
            .load("application", ClassPathResource("application.yaml"))
            .single()

        propertySource.getProperty("wow.webflux.query.max-filter-nodes")
            .assert().isEqualTo(128)
    }

    @Test
    fun `should mirror query filter node limit in distribution`() {
        val propertySource = YamlPropertySourceLoader()
            .load("application", FileSystemResource("src/dist/config/application.yaml"))
            .single()

        propertySource.getProperty("wow.webflux.query.max-filter-nodes")
            .assert().isEqualTo(128)
    }
}
