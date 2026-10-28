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

package me.ahoo.wow.openapi

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.CLASS_KEYWORD
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.ManifestMerger
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.ServiceInfo
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The documents of the eShop's five services (decision D159), from their domains' Manifest fragments merged as the
 * host merges them, against the snapshots in `src/test/resources/openapi/`. A change to the generator or to the eShop's
 * contract shows here as a snapshot diff; `-Dwow.snapshot.update=true` rewrites them.
 */
class EShopOpenApiSnapshotTest {
    @ParameterizedTest
    @ValueSource(strings = ["catalog", "basket", "ordering", "inventory", "payment"])
    fun `each eShop service's document should match its snapshot and be well formed`(context: String) {
        val document = ManifestOpenApi.write(manifest(context))
        OpenApiChecks.check(document)
        assertSnapshot(document, SNAPSHOTS.resolve("eshop-$context-service.json"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["catalog", "basket", "ordering", "inventory", "payment"])
    fun `a public document should name no JVM class, which the Manifest's schemas carry`(context: String) {
        val manifest = manifest(context)
        OpenApiChecks.mapper.writeValueAsString(manifest.defs).assert().contains("\"$CLASS_KEYWORD\"")
        OpenApiChecks.mapper.writeValueAsString(ManifestOpenApi.write(manifest)).assert()
            .doesNotContain("\"$CLASS_KEYWORD\"")
    }

    @Test
    fun `a command result as the edge writes it should match the document's schema`() {
        val document = ManifestOpenApi.write(manifest("ordering"))
        OpenApiChecks.validate(document, WowSchemas.COMMAND_RESULT, COMMAND_RESULT).assert().isEmpty()
        OpenApiChecks.validate(document, WowSchemas.COMMAND_RESULT, """{"format":1}""").assert().isNotEmpty()
    }

    @Test
    fun `a missing snapshot should fail outside update mode`() {
        assumeFalse(UPDATE)
        val missing = Files.createTempDirectory("wow-openapi").resolve("missing.json")
        assertThrows<AssertionError> { assertSnapshot(mapOf("openapi" to "3.1.0"), missing) }
            .message.assert().contains("-Dwow.snapshot.update=true")
    }

    companion object {
        private val SNAPSHOTS: Path = Path.of("src/test/resources/openapi")
        private val UPDATE = System.getProperty("wow.snapshot.update").equals("true", ignoreCase = true)

        /** A result as `wow-http` writes it (`HttpJson`), every member set. */
        private const val COMMAND_RESULT = """{"format":1,"commandId":"c","requestId":"r","correlationId":"co",
            "ref":{"type":{"context":"c","name":"a"},"tenantId":"t","id":"i"},
            "hop":{"stage":"handled","context":"c","subscription":"s"},
            "function":{"kind":"saga","context":"c","processor":"p","name":"n"},"status":"failed","version":1,
            "duplicate":false,"error":{"code":"x","message":"m","violations":[{"path":"p","code":"c","message":"m"}]},
            "results":{},"derived":[],"signalTime":1,"transformedFrom":{"command":"x","aggregateId":"y"},
            "chainEnded":true,"pending":[{"stage":"committed","context":"c"}]}"""

        /** The Manifest of the eShop service of [context], merged from its domain's fragment. */
        fun manifest(context: String): Manifest {
            val source = "${Manifest.FRAGMENT_DIRECTORY}/eshop-$context-domain.json"
            val bytes = checkNotNull(
                javaClass.classLoader.getResourceAsStream(source)
            ) { "$source is not on the class path" }
                .use { it.readBytes() }
            val fragment = ManifestTree.read(OpenApiChecks.mapper.readValue(bytes, Map::class.java))
            return ManifestMerger.merge(
                listOf(ManifestFragment(source, fragment)),
                ServiceInfo("eshop-$context-service")
            )
        }

        fun assertSnapshot(document: Map<String, Any?>, snapshot: Path) {
            val actual = OpenApiChecks.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n"
            if (UPDATE) {
                snapshot.parent.createDirectories()
                snapshot.writeText(actual)
                return
            }
            if (!snapshot.exists()) {
                throw AssertionError("Missing snapshot $snapshot: run with -Dwow.snapshot.update=true to write it.")
            }
            actual.assert().isEqualTo(snapshot.readText())
        }
    }
}
