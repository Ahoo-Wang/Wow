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

package me.ahoo.wow.architecture

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The key boundaries as positive constraints, and the 9.x names no module carries (review R5.C-10). */
class PositiveRulesTest {
    private val repositoryRoot: File =
        File(requireNotNull(System.getProperty(REPOSITORY_ROOT)) { "System property $REPOSITORY_ROOT is not set." })

    @TestFactory
    fun `core modules should import only what their allow-list names`(): List<DynamicTest> =
        PositiveRules.CORE_IMPORTS.map { rule ->
            DynamicTest.dynamicTest(rule.name) {
                rule.violations(repositoryRoot).assert().describedAs("Rule violated: ${rule.name}").isEmpty()
            }
        }

    @Test
    fun `an allow-list should catch an import it does not name, aliases and wildcards included`(@TempDir root: File) {
        File(root, "core/spi/src/main/kotlin/Port.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package me.ahoo.wow.spi

                import kotlin.time.Duration
                import java.util.UUID
                import kotlinx.coroutines.flow.Flow
                import me.ahoo.wow.api.aggregate.AggregateRef
                import me.ahoo.wow.runtime.node.ServiceNode
                import reactor.core.publisher.Mono as M
                import org.springframework.context.*
                """.trimIndent(),
            )
        }
        val spi = PositiveRules.CORE_IMPORTS.single { it.modules == listOf(ArchitectureRules.SPI.dir) }
        spi.violations(root).assert().containsExactly(
            "core/spi/src/main/kotlin/Port.kt:7: me.ahoo.wow.runtime.node.ServiceNode",
            "core/spi/src/main/kotlin/Port.kt:8: reactor.core.publisher.Mono",
            "core/spi/src/main/kotlin/Port.kt:9: org.springframework.context",
        )
    }

    @Test
    fun `no module but the 9x import should name a 9x type`() {
        PositiveRules.nineXNameViolations(repositoryRoot).assert().isEmpty()
        // The detectors are still there, and still the only sources that name 9.x packages.
        PositiveRules.NINE_X_DETECTORS.forEach { File(repositoryRoot, it).isFile.assert().describedAs(it).isTrue() }
        PositiveRules.NINE_X_NAMES.violations(repositoryRoot).map { it.substringBefore(':') }.toSet()
            .assert().isEqualTo(PositiveRules.NINE_X_DETECTORS)
    }

    @Test
    fun `the 9x name rule should catch a 9x package, and not a Wow 10 package of a similar name`(@TempDir root: File) {
        File(root, "${ArchitectureRules.RUNTIME.dir}/src/main/kotlin/Old.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                import me.ahoo.wow.modeling.state.StateAggregate
                import me.ahoo.wow.api.aggregate.AggregateRef
                import me.ahoo.wow.query.engine.QueryEngine
                val bus = "me.ahoo.wow.command.CommandBus"
                import me.ahoo.wow.api.naming.TypeNames
                import me.ahoo.wow.api.naming.NamedBoundedContext
                """.trimIndent(),
            )
        }
        PositiveRules.nineXNameViolations(root).assert().containsExactly(
            "${ArchitectureRules.RUNTIME.dir}/src/main/kotlin/Old.kt:1: me.ahoo.wow.modeling.state.StateAggregate",
            "${ArchitectureRules.RUNTIME.dir}/src/main/kotlin/Old.kt:4: me.ahoo.wow.command.CommandBus",
            "${ArchitectureRules.RUNTIME.dir}/src/main/kotlin/Old.kt:6: me.ahoo.wow.api.naming.NamedBoundedContext",
        )
    }

    @Test
    fun `no configuration Wow 10 knows or writes should have a 9x key`() {
        PositiveRules.nineXKeyViolations(repositoryRoot, PositiveRules.CONFIG_DIRS).assert().isEmpty()
    }

    @Test
    fun `the 9x key rule should read the metadata and nested YAML and properties keys`(@TempDir root: File) {
        File(root, "host/spring-boot/src/main/resources/META-INF/spring-configuration-metadata.json").apply {
            parentFile.mkdirs()
            writeText("""{"properties": [{"name" : "wow.query.max-wait"}, {"name" : "wow.eventsourcing.store"}]}""")
        }
        File(root, "example/shop/src/main/resources/application.yaml").apply {
            parentFile.mkdirs()
            writeText(
                """
                # wow.command.bus: a comment
                spring:
                  application:
                    name: shop
                wow:
                  command:
                    bus:
                      type: kafka
                  aggregates:
                    "[shop.order]":
                      tenant: platform
                  topics:
                    - wow.webflux
                """.trimIndent(),
            )
        }
        File(root, "platform/x/src/main/resources/application-dev.properties").apply {
            parentFile.mkdirs()
            writeText("wow.webflux.global-error.enabled=false\nwow.tenant.default=t\n")
        }
        PositiveRules.nineXKeyViolations(root, listOf("example", "platform")).assert().containsExactly(
            "host/spring-boot/src/main/resources/META-INF/spring-configuration-metadata.json: wow.eventsourcing.store",
            "example/shop/src/main/resources/application.yaml: wow.command",
            "example/shop/src/main/resources/application.yaml: wow.command.bus",
            "example/shop/src/main/resources/application.yaml: wow.command.bus.type",
            "platform/x/src/main/resources/application-dev.properties: wow.webflux.global-error.enabled",
        )
    }

    private companion object {
        const val REPOSITORY_ROOT = "wow.repository.root"
    }
}
