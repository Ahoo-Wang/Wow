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

package me.ahoo.wow.manifest

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.Collections
import java.util.Enumeration

class ServiceBuildTest {
    @TempDir
    lateinit var directory: File

    private val mapper = jacksonObjectMapper()

    /** A class loader over a directory per entry of [builds], each holding its text at [ServiceBuild.RESOURCE]. */
    private fun loader(vararg builds: Pair<String, String>): ClassLoader = URLClassLoader(
        builds.map { (name, text) ->
            File(directory, name).also { File(it, ServiceBuild.RESOURCE).apply { parentFile.mkdirs() }.writeText(text) }
                .toURI().toURL()
        }.toTypedArray(),
        null,
    )

    private fun read(loader: ClassLoader): List<ServiceBuild> =
        ServiceBuild.load(loader) { bytes -> mapper.readValue(bytes, Map::class.java) }

    @Test
    fun `the tree of a merged Manifest should name its service and its contexts, sorted`() {
        val manifest = Manifest(
            service = ServiceInfo("bank-service", "1.0.0"),
            contexts = listOf(ContextManifest("ledger"), ContextManifest("bank")),
        )
        ServiceBuild.treeOf(manifest).assert()
            .isEqualTo(mapOf("name" to "bank-service", "contexts" to listOf("bank", "ledger")))
        assertThrows<IllegalArgumentException> { ServiceBuild.treeOf(Manifest()) }
    }

    @Test
    fun `each build on the class path should be read, the same build once`() {
        val text = """{"name":"bank-service","contexts":["bank"]}"""
        val builds = read(
            loader(
                "bank" to text,
                "copy" to text,
                "ledger" to """{"name":"ledger-service","contexts":["ledger"],"later":true}""",
            ),
        )
        builds.map { it.name to it.contexts }.assert()
            .containsExactly("bank-service" to listOf("bank"), "ledger-service" to listOf("ledger"))
        builds.first().source.assert().endsWith("bank/${ServiceBuild.RESOURCE}")
        read(loader()).assert().isEmpty()
    }

    @Test
    fun `a build that is not JSON, or names no service and its contexts, should be refused, each listed`() {
        val failure = assertThrows<ContractException> {
            read(
                loader(
                    "broken" to "{",
                    "nameless" to """{"contexts":["bank"]}""",
                    "blank" to """{"name":" ","contexts":[]}""",
                    "contextless" to """{"name":"bank-service"}""",
                    "numbers" to """{"name":"bank-service","contexts":[1]}""",
                ),
            )
        }
        failure.problems.size.assert().isEqualTo(5)
        failure.problems.first().assert().startsWith("service build ").contains("broken/", "is not JSON")
        failure.problems.drop(1).forEach { it.assert().contains("names no service and its contexts") }
    }

    @Test
    fun `a build whose JSON is no object, or whose name is no text, should name no service`() {
        val loader = loader(
            "array" to "[]",
            "text" to "\"bank-service\"",
            "null" to "null",
            "number" to """{"name":1,"contexts":["bank"]}""",
        )
        val failure = assertThrows<ContractException> {
            ServiceBuild.load(loader) { bytes -> mapper.readValue(bytes, Any::class.java) }
        }
        failure.problems.size.assert().isEqualTo(4)
        failure.problems.forEach { it.assert().contains("names no service and its contexts") }
    }

    @Test
    fun `a build that cannot be read should be refused, saying so`() {
        val missing = File(directory, "gone/${ServiceBuild.RESOURCE}").toURI().toURL()
        val loader = object : ClassLoader(null) {
            override fun getResources(name: String): Enumeration<URL> =
                Collections.enumeration(if (name == ServiceBuild.RESOURCE) listOf(missing) else emptyList())
        }
        assertThrows<ContractException> { read(loader) }.problems.single().assert()
            .startsWith("service build $missing cannot be read: java.io.FileNotFoundException")
    }
}
