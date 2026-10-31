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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Decision D289: a meter is named by a constant of the metric catalog (`WowMetrics`), where the naming rules are
 * tested, never by a name spelled out beside it.
 */
class MetricNamesTest {
    private val repositoryRoot: File =
        File(requireNotNull(System.getProperty(REPOSITORY_ROOT)) { "System property $REPOSITORY_ROOT is not set." })
            .canonicalFile

    @Test
    fun `no production code should name a meter outside the metric catalog`() {
        ArchitectureRules.literalMeterNames(repositoryRoot, ALLOWED).assert()
            .describedAs("Add the name to WowMetrics and register the meter by its constant.")
            .isEmpty()
    }

    @Test
    fun `every allowed file should still name a meter`() {
        ArchitectureRules.staleLiteralMeterNames(repositoryRoot, ALLOWED).assert()
            .describedAs("Remove these entries: their files name no meter by a literal any more.")
            .isEmpty()
    }

    @Test
    fun `the rule should catch a literal and a template, not a constant`(@TempDir root: File) {
        File(root, "${ArchitectureRules.RUNTIME.dir}/src/main/kotlin/me/ahoo/wow/runtime/Meters.kt").apply {
            parentFile.mkdirs()
        }.writeText(
            """
            package me.ahoo.wow.runtime

            val a = Counter.builder("wow.some.thing").register(registry)
            val b = registry.timer("${'$'}PREFIX.duration", "context", "c")
            val c = Gauge.builder(WowMetrics.EXECUTOR_LANES, holder) { 1.0 }
            val d = registry.counter(name, *tags)
            """.trimIndent(),
        )
        ArchitectureRules.literalMeterNames(root).map { it.substringAfter("Meters.kt:").substringBefore(':') }
            .assert().containsExactly("3", "4")
    }

    private companion object {
        const val REPOSITORY_ROOT = "wow-test.repository.root"

        /**
         * The files that may, each with its reason. None: the framework's modules name every meter through the
         * catalog. The platform servers are applications, outside the modules scanned: the analytics catalog's own
         * gauges (`wow.analytics.catalog.datasets.*`) stay under its configuration prefix.
         */
        val ALLOWED: Map<String, String> = emptyMap()
    }
}
