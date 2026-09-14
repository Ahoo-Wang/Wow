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

package me.ahoo.wow.spring.boot.starter.elasticsearch

import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import java.time.Duration

class ElasticsearchQueryPropertiesTest {
    @Test
    fun `should reject invalid query settings`() {
        assertThrownBy<IllegalArgumentException> {
            ElasticsearchQueryProperties(batchSize = 0)
        }
        assertThrownBy<IllegalArgumentException> {
            ElasticsearchQueryProperties(batchSize = 10_001)
        }
        assertThrownBy<IllegalArgumentException> {
            ElasticsearchQueryProperties(keepAlive = Duration.ZERO)
        }
    }

    @Test
    fun `should reject invalid query settings through configuration properties binding`() {
        listOf(
            "${ElasticsearchQueryProperties.PREFIX}.batch-size=0" to "batchSize must be between 1 and 10000.",
            "${ElasticsearchQueryProperties.PREFIX}.keep-alive=0ms" to "keepAlive must be greater than or equal to 1ms.",
        ).forEach { (property, expectedMessage) ->
            ApplicationContextRunner()
                .withPropertyValues(property)
                .withUserConfiguration(ConfigurationPropertiesConfiguration::class.java)
                .run { context ->
                    val startupFailure = context.startupFailure
                    assertTrue(
                        startupFailure?.let { failure ->
                            generateSequence(failure) { it.cause }
                                .any { it.message?.contains(expectedMessage) == true }
                        } == true,
                        "Expected binding to reject [$property] with [$expectedMessage], but was [$startupFailure]",
                    )
                }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ElasticsearchQueryProperties::class)
    private class ConfigurationPropertiesConfiguration
}
