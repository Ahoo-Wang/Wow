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
 * An output's `oneOf` (#6285): a value old clients read matches exactly one old alternative, so a current alternative
 * that matches one old alternative may still produce a value another old one accepts too, which old clients refuse.
 * Removing or narrowing an alternative that overlaps another (`number` beside `integer`) produces such values.
 */
class OutputOneOfCompatibilityTest {
    private val number = mapOf("type" to "number")
    private val integer = mapOf("type" to "integer")
    private val string = mapOf("type" to "string")
    private fun oneOf(vararg alternatives: Map<String, Any?>) = mapOf("oneOf" to alternatives.toList())
    private fun anyOf(vararg alternatives: Map<String, Any?>) = mapOf("anyOf" to alternatives.toList())

    /** `number` or `integer`: an integer matches both, so old clients read only non-integral numbers. */
    private val overlapping = oneOf(number, integer)

    /** A route whose request ([input]) or response holds a required `value` of [value]. */
    private fun manifest(value: Map<String, Any?>, input: Boolean = false): Manifest {
        val shape = mapOf("type" to "object", "properties" to mapOf("value" to value), "required" to listOf("value"))
        val route = ServiceRouteManifest(
            name = "summary",
            route = RouteManifest("POST", "/shop/summaries"),
            request = shape.takeIf { input },
            response = shape.takeUnless { input },
        )
        return BASELINE.copyWith(service = ServiceInfo("shop-service", "1.1.0")).context {
            it.copyWith(routes = listOf(route))
        }
    }

    private fun changes(before: Map<String, Any?>, after: Map<String, Any?>, input: Boolean = false) =
        ManifestCompatibility.compare(manifest(before, input), manifest(after, input))

    @Test
    fun `removing an alternative another overlaps should break the response`() {
        changes(overlapping, oneOf(number)).breaking.map { it.path }.assert()
            .containsExactly("contexts[shop].routes[summary].response.value.oneOf[0]")
    }

    @Test
    fun `narrowing an alternative another overlaps should break the response`() {
        changes(overlapping, oneOf(number, integer + ("minimum" to 10))).breaking.assert().isNotEmpty()
    }

    @Test
    fun `the same overlapping alternatives should be no change`() {
        changes(overlapping, overlapping).changes.assert().isEmpty()
    }

    @Test
    fun `a stable service should not record the widened response as its baseline`(@TempDir directory: File) {
        val baseline = File(directory, "baseline.json")
        val current = File(directory, "current.json")
        Baseline.of(manifest(overlapping), 1).write(baseline)
        ManifestJson.write(manifest(oneOf(number)), current)
        val before = baseline.readText()
        assertThrows<IllegalStateException> {
            ManifestTools.recordBaseline(current, baseline, false, File(directory, "record.txt"), "shop")
        }
        baseline.readText().assert().isEqualTo(before)
    }

    @Test
    fun `removing an overlapping alternative of a request should only widen it`() {
        changes(overlapping, oneOf(number), input = true).breaking.assert().isEmpty()
    }

    @Test
    fun `removing a redundant alternative of an anyOf response should not break it`() {
        changes(anyOf(number, integer), anyOf(number)).breaking.assert().isEmpty()
    }

    @Test
    fun `removing an alternative no other overlaps should only narrow the response`() {
        changes(oneOf(number, string), oneOf(number)).changes.map { "${it.kind} ${it.description}" }.assert()
            .containsExactly("ADDITIVE alternative no longer produced")
    }
}
