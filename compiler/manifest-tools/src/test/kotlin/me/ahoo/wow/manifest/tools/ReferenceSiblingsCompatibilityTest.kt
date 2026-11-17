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

package me.ahoo.wow.manifest.tools

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.ServiceRouteManifest
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.BASELINE
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.context
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A `$ref` and the keywords beside it both hold (#6286): a keyword the reference's definition has too does not replace
 * the definition's, so a change of the definition's is still compared.
 */
class ReferenceSiblingsCompatibilityTest {
    private fun number(minimum: Int) = mapOf("type" to "number", "minimum" to minimum)

    /** A route whose request (or [output], response) holds a required `value`, a reference to `Value` with [siblings]. */
    private fun manifest(
        definition: Map<String, Any?>,
        siblings: Map<String, Any?>,
        output: Boolean = false,
    ): Manifest {
        val field = Manifest.refSchema("Value") + siblings
        val shape = mapOf("type" to "object", "properties" to mapOf("value" to field), "required" to listOf("value"))
        val route = ServiceRouteManifest(
            name = "summary",
            route = RouteManifest("POST", "/shop/summaries"),
            request = shape.takeUnless { output },
            response = shape.takeIf { output },
        )
        val service = ServiceInfo("shop-service", "1.1.0")
        return BASELINE.copyWith(service = service, defs = BASELINE.defs + ("Value" to definition))
            .context { it.copyWith(routes = listOf(route)) }
    }

    private fun changes(
        old: Map<String, Any?>,
        new: Map<String, Any?>,
        siblings: Map<String, Any?>,
        output: Boolean = false,
    ) = ManifestCompatibility.compare(manifest(old, siblings, output), manifest(new, siblings, output))

    @Test
    fun `a request's definition bound raised beside a weaker sibling bound should break`() {
        changes(number(10), number(20), mapOf("minimum" to 0)).breaking.assert().isNotEmpty()
    }

    @Test
    fun `a response's definition bound lowered beside a weaker sibling bound should break`() {
        changes(number(20), number(10), mapOf("minimum" to 0), output = true).breaking.assert().isNotEmpty()
    }

    @Test
    fun `a definition bound moved under a stronger sibling bound should be no change`() {
        changes(number(10), number(20), mapOf("minimum" to 30)).changes.assert().isEmpty()
    }

    @Test
    fun `a definition pattern changed beside another pattern should break`() {
        val old = mapOf("type" to "string", "pattern" to "^a")
        changes(old, old + ("pattern" to "^b"), mapOf("pattern" to ".*")).breaking.assert().isNotEmpty()
    }

    @Test
    fun `a definition refusing additional properties beside a sibling allowing them should break`() {
        val properties = mapOf("x" to mapOf("type" to "string"))
        val old = mapOf("type" to "object", "properties" to properties, "additionalProperties" to true)
        changes(old, old + ("additionalProperties" to false), mapOf("additionalProperties" to true))
            .breaking.assert().isNotEmpty()
    }

    @Test
    fun `a stable service should not record a request break a sibling hid as its baseline`(@TempDir directory: File) {
        val baseline = File(directory, "baseline.json")
        val current = File(directory, "current.json")
        Baseline.of(manifest(number(10), mapOf("minimum" to 0)), 1).write(baseline)
        ManifestJson.write(manifest(number(20), mapOf("minimum" to 0)), current)
        val before = baseline.readText()
        assertThrows<IllegalStateException> {
            ManifestTools.recordBaseline(current, baseline, false, File(directory, "record.txt"), "shop")
        }
        baseline.readText().assert().isEqualTo(before)
    }

    @Test
    fun `a definition bound without a sibling should still be compared`() {
        changes(number(10), number(20), emptyMap()).breaking.assert().isNotEmpty()
    }

    @Test
    fun `a stronger sibling bound raised should still break`() {
        ManifestCompatibility.compare(
            manifest(number(0), mapOf("minimum" to 10)),
            manifest(number(0), mapOf("minimum" to 20)),
        ).breaking.assert().isNotEmpty()
    }

    @Test
    fun `the same definition and siblings should be no change`() {
        changes(number(10), number(10), mapOf("minimum" to 0)).changes.assert().isEmpty()
    }
}
