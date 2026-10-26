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
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** The auto-configurations of the optional modules: each listed, and active only when its module is on the classpath. */
class ModuleAutoConfigurationsTest {
    private val configurations = mapOf(
        QueryAutoConfiguration::class.java to QUERY_MODULE,
        ClickHouseAutoConfiguration::class.java to CLICKHOUSE_MODULE,
    )

    @Test
    fun `every module auto-configuration should be listed in the imports`() {
        val imports = requireNotNull(javaClass.classLoader.getResource(IMPORTS)) { "$IMPORTS is missing." }
            .readText().lines().map { it.trim() }
        imports.assert().containsAll(configurations.keys.map { it.name })
    }

    @TestFactory
    fun `a module auto-configuration should follow its module on the classpath`(): List<DynamicTest> =
        configurations.map { (configuration, marker) ->
            DynamicTest.dynamicTest(configuration.simpleName) {
                val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(configuration))
                runner.run { context ->
                    context.getBeansOfType(configuration).assert().hasSize(1)
                }
                runner.withClassLoader(FilteredClassLoader(marker)).run { context ->
                    context.getBeansOfType(configuration).assert().isEmpty()
                }
            }
        }

    private companion object {
        const val IMPORTS = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
    }
}
