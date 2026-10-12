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
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.Manifest.Companion.schemaRef
import me.ahoo.wow.manifest.ManifestConflictException
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ManifestToolsTest {
    class CartState(val id: String)

    class AccountState(val id: String, val holder: String? = null)

    @TempDir
    lateinit var directory: File

    private fun fragment(context: String, state: Class<*>, defs: Map<String, Any?> = emptyMap()): Manifest = Manifest(
        contexts = listOf(
            ContextManifest(
                name = context,
                aggregates = listOf(
                    AggregateManifest(name = "aggregate", state = StateManifest(schemaRef(state.name)))
                ),
            ),
        ),
        defs = defs,
    )

    /** A class path directory holding [manifests] as fragments, by module name. */
    private fun classesDirectory(name: String, vararg manifests: Pair<String, Manifest>): File =
        File(directory, name).also { classes ->
            manifests.forEach { (module, manifest) ->
                ManifestJson.write(manifest, File(classes, "${Manifest.FRAGMENT_DIRECTORY}/$module.json"))
            }
            // Neither a nested directory nor another file type is a fragment.
            File(
                classes,
                "${Manifest.FRAGMENT_DIRECTORY}/nested/ignored.json"
            ).apply { parentFile.mkdirs() }.writeText("{}")
            File(classes, "${Manifest.FRAGMENT_DIRECTORY}/README.txt").writeText("not a fragment")
        }

    /** A jar holding [manifest] as the fragment of [module]. */
    private fun jar(module: String, manifest: Manifest): File {
        val fragment = File(directory, "$module-fragment.json").also { ManifestJson.write(manifest, it) }
        return File(directory, "$module.jar").also { jar ->
            ZipOutputStream(jar.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("${Manifest.FRAGMENT_DIRECTORY}/"))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("${Manifest.FRAGMENT_DIRECTORY}/$module.json"))
                zip.write(fragment.readBytes())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("${Manifest.FRAGMENT_DIRECTORY}/nested/ignored.json"))
                zip.write("{}".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("sample/Other.class"))
                zip.closeEntry()
            }
        }
    }

    @Test
    fun `completing a fragment should write its schemas and fingerprints`() {
        val skeleton = File(
            directory,
            "skeleton.json"
        ).also { ManifestJson.write(fragment("shop", CartState::class.java), it) }
        val output = File(directory, "out/${Manifest.FRAGMENT_DIRECTORY}/shop.json")

        ManifestTools.completeFragment(skeleton, output, emptyMap())

        val completed = ManifestJson.read(output)
        completed.unresolvedSchemaRefs.assert().isEmpty()
        completed.contexts.single().aggregates.single().state.fingerprint.assert().hasSize(64)
    }

    @Test
    fun `merging should read the fragments of directories and jars`() {
        val shop =
            fragment("shop", CartState::class.java, mapOf(CartState::class.java.name to mapOf("type" to "object")))
        val billing =
            fragment(
                "billing",
                AccountState::class.java,
                mapOf(AccountState::class.java.name to mapOf("type" to "object"))
            )
        val output = File(directory, "manifest.json")
        val classpath = listOf(
            classesDirectory("classes", "shop" to shop),
            jar("billing", billing),
            File(directory, "missing.jar"),
            File(directory, "classes-without-fragments").apply { mkdirs() },
        )

        ManifestTools.merge(classpath, "store", "1.0.0", output).assert().isEqualTo(2)

        val manifest = ManifestJson.read(output)
        manifest.service.assert().isEqualTo(ServiceInfo("store", "1.0.0"))
        manifest.contexts.map { it.name }.assert().containsExactly("billing", "shop")
    }

    @Test
    fun `merging should refuse conflicting fragments`() {
        val shop =
            fragment("shop", CartState::class.java, mapOf(CartState::class.java.name to mapOf("type" to "object")))
        val classpath = listOf(classesDirectory("a", "shop" to shop), jar("other", shop))
        assertThrows<ManifestConflictException> {
            ManifestTools.merge(classpath, "store", null, File(directory, "manifest.json"))
        }.conflicts.single().assert().startsWith("context shop is declared by")
    }

    @Test
    fun `merging should refuse a schema no fragment holds`() {
        val classpath = listOf(classesDirectory("classes", "shop" to fragment("shop", CartState::class.java)))
        assertThrows<IllegalStateException> {
            ManifestTools.merge(classpath, "store", null, File(directory, "manifest.json"))
        }.message.assert().contains("refers to schemas no fragment holds").contains(CartState::class.java.name)
        File(directory, "manifest.json").exists().assert().isFalse()
    }
}
