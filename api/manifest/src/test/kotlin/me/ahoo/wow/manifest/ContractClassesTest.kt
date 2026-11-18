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
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI
import java.net.URLClassLoader

/** Whether the classes of a peer contract are on its consumer's class path ([ContractClasses]). */
class ContractClassesTest {
    private val contract = ManifestFragment(
        "sample-api-1.0.0-contract.jar",
        Manifest(
            contexts = listOf(
                ContextManifest(
                    "sample",
                    aggregates = listOf(
                        AggregateManifest(
                            "order",
                            state = StateManifest(Manifest.schemaRef("sample.OrderState")),
                            commands = listOf(CommandManifest("Open", schema = Manifest.schemaRef("sample.Open"))),
                            events = listOf(EventManifest("Opened", schema = Manifest.schemaRef("sample.Opened"))),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `a context of which only some classes are present should name the contract and the types beside it`() {
        val types = mapOf("sample.Open" to "sample-api-2.0.0.jar")
        ContractClasses.problems(contract, types::get).single().assert().isEqualTo(
            "sample.Opened, which the contract in sample-api-1.0.0-contract.jar lists for context sample, is in no " +
                "class path entry, while the context's other classes are in sample-api-2.0.0.jar: the contract and " +
                "the types of its api module are of different versions. Depend on one version of the module, its " +
                "types and its contract (Maven: the module and its classifier contract, of one version).",
        )
    }

    @Test
    fun `a context of which all classes, or none, are present should pass in silence`() {
        ContractClasses.problems(contract) { "sample-api-1.0.0.jar" }.assert().isEmpty()
        // A peer addressed by its contract alone, its commands as JSON.
        ContractClasses.problems(contract) { null }.assert().isEmpty()
    }

    @Test
    fun `a class should be located in its jar, by the jar's name, or in its directory`(@TempDir directory: File) {
        ContractClasses.where(URI("jar:file:/repo/sample-api-2.0.0.jar!/sample/Open.class").toURL(), "sample.Open")
            .assert().isEqualTo("sample-api-2.0.0.jar")
        File(directory, "sample/Open.class").apply { parentFile.mkdirs() }.writeText("")
        URLClassLoader(arrayOf(directory.toURI().toURL()), null).use { loader ->
            val locate = ContractClasses.locator(loader)
            locate("sample.Open").assert().isEqualTo(directory.toURI().toURL().path)
            locate("sample.Opened").assert().isNull()
        }
    }
}
