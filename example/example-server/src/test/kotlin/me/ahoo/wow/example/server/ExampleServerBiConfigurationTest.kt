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

package me.ahoo.wow.example.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.FileSystemResource
import java.nio.file.Path

class ExampleServerBiConfigurationTest {
    @Test
    fun `source and distribution configurations should expose a usable BI script endpoint`() {
        CONFIGURATIONS.forEach { configuration ->
            val properties = YamlPropertySourceLoader()
                .load(configuration.toString(), FileSystemResource(configuration))

            assertEquals(
                true,
                properties.firstNotNullOfOrNull { it.getProperty(BI_ENABLED_PROPERTY) },
                "$configuration must explicitly enable the BI script endpoint",
            )
            assertEquals(
                CONSUMER_GROUP_NAMESPACE,
                properties.firstNotNullOfOrNull { it.getProperty(CONSUMER_GROUP_NAMESPACE_PROPERTY) },
                "$configuration must provide a stable BI consumer group namespace",
            )
        }
    }

    private companion object {
        const val BI_ENABLED_PROPERTY = "wow.bi.script.enabled"
        const val CONSUMER_GROUP_NAMESPACE_PROPERTY = "wow.bi.script.consumer-group-namespace"
        const val CONSUMER_GROUP_NAMESPACE = "example-service-local"
        val CONFIGURATIONS = listOf(
            Path.of("src/main/resources/application.yaml"),
            Path.of("src/dist/config/application.yaml"),
        )
    }
}
