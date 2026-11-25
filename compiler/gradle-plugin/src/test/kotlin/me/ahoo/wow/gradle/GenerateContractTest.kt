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

package me.ahoo.wow.gradle

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.tools.ManifestJson
import org.gradle.api.Project
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The contract generation ([GenerateContract]): the completed fragments filtered down, the service named, the pairing refused. */
class GenerateContractTest {
    @TempDir
    lateinit var directory: File

    /** A project of [id], the service plugin by default: the domain role on its sources, and the service's. */
    private fun project(kotlin: Boolean = true, id: String = WowPlugin.SERVICE_ID): Project =
        wowProject(directory, kotlin, id)

    @Test
    fun `generating should filter the completed fragments down to the contract`() {
        val project = project()
        project.extensions.getByType(WowExtension::class.java).contractPackage.set("com.example.shop.api")
        val fragment = File(directory, "fragments/${Manifest.FRAGMENT_DIRECTORY}/shop.json")
        ManifestJson.write(Manifest(contexts = listOf(ContextManifest("shop"))), fragment)
        val task = (project.tasks.getByName(WowPlugin.GENERATE_CONTRACT_TASK) as GenerateContract).apply {
            fragments.setFrom(fragment)
            // The projects of the module's class path and their pairing, as the build resolves them.
            classpathProjects.set(emptyMap())
        }

        task.submit(inProcessQueue(project))

        val contract = ManifestJson.read(File(directory, "build/wow/contract.json"))
        contract.service.assert().isNull()
        contract.contexts.map { it.name }.assert().containsExactly("shop")
        File(directory, "build/wow/${ApiRole.CONTRACT_PACKAGES_FILE}").readText().assert()
            .isEqualTo("shop=com.example.shop.api\n")
    }

    @Test
    fun `a contract names the service its module declares`() {
        val project = project()
        project.extensions.getByType(WowExtension::class.java).service.set("shop-service")
        project.extensions.getByType(WowExtension::class.java).contractPackage.set("com.example.shop.api")
        val fragment = File(directory, "fragments/${Manifest.FRAGMENT_DIRECTORY}/shop.json")
        ManifestJson.write(Manifest(contexts = listOf(ContextManifest("shop"))), fragment)
        (project.tasks.getByName(WowPlugin.GENERATE_CONTRACT_TASK) as GenerateContract).apply {
            fragments.setFrom(fragment)
            classpathProjects.set(emptyMap())
        }.submit(inProcessQueue(project))

        ManifestJson.read(File(directory, "build/wow/contract.json")).service.assert()
            .isEqualTo(ServiceInfo("shop-service"))
    }

    @Test
    fun `a context whose classes are in a project that does not pair should fail, naming the line to add`() {
        val project = project(id = WowPlugin.ID)
        project.extensions.getByType(WowExtension::class.java).contractPackage.set("sample")
        val fragment = File(directory, "fragments/${Manifest.FRAGMENT_DIRECTORY}/shop.json")
        val aggregate = AggregateManifest(
            name = "cart",
            state = StateManifest(Manifest.schemaRef("sample.CartState")),
            commands = listOf(CommandManifest("AddItem", schema = Manifest.schemaRef("sample.AddItem"))),
        )
        ManifestJson.write(
            Manifest(
                contexts = listOf(ContextManifest("shop", aggregates = listOf(aggregate))),
                defs = mapOf("sample.CartState" to mapOf("type" to "object")),
            ),
            fragment,
        )
        val apiClasses = File(directory, "shop-api/build/classes/kotlin/main")
        File(apiClasses, "sample/AddItem.class").apply { parentFile.mkdirs() }.writeText("")
        val task = (project.tasks.getByName(WowPlugin.GENERATE_CONTRACT_TASK) as GenerateContract).apply {
            fragments.setFrom(fragment)
            classpathProjects.set(mapOf(apiClasses.path to ":shop-api", File(directory, "missing").path to ":other"))
            // The api module pairs with another domain module only.
            pairing.setFrom(pairing(":shop-api", ":billing-domain"))
        }
        val queue = inProcessQueue(project)
        assertThrows<IllegalStateException> { task.submit(queue) }.message.assert().contains(
            "The contract of project ':' reaches no api module",
            "context shop has its classes sample.AddItem in project ':shop-api', which does not pair with project ':'",
            "apply id(\"me.ahoo.wow.api\") and add dependencies { wowContract(project(\":\")) }",
        )
        // Paired: its pairing file names the module among others.
        task.pairing.setFrom(pairing(":shop-api", ":billing-domain", ":"))
        task.unpaired().assert().isEqualTo(mapOf(File(directory, "missing").path to ":other"))
        task.classpathProjects.set(mapOf(apiClasses.path to ":shop-api"))
        task.submit(queue)
        ManifestJson.read(
            File(directory, "build/wow/contract.json")
        ).contexts.map { it.name }.assert().containsExactly("shop")
    }

    /** A pairing file of the api module [api] that declares [domains], as `wowPairingElements` holds it. */
    private fun pairing(api: String, vararg domains: String): File =
        File(directory, "pairing-${domains.size}.properties").apply {
            writeText("${ApiRole.PAIRING_API}=$api\n${ApiRole.PAIRING_DOMAINS}=${domains.joinToString(",")}\n")
        }
}
