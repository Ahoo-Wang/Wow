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

package me.ahoo.wow.runtime.manifest

import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.MissingDependencyException
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestConflictException
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.cluster.accountManifest
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.NODE_SERVICE
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.accountModel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.net.URLClassLoader
import java.time.Clock

/** The module of context `bank`, as the compiler generates its binding index. */
class BankModels : ModelProvider {
    override val context = "bank"
    override val fragment = "META-INF/wow/manifest/bank.json"

    override fun models(dependencies: Dependencies): List<AggregateModel<*>> = listOf(accountModel(context))
}

/** The module of context `ledger`. */
class LedgerModels : ModelProvider {
    override val context = "ledger"
    override val fragment = "META-INF/wow/manifest/ledger.json"

    override fun models(dependencies: Dependencies): List<AggregateModel<*>> = listOf(accountModel(context))
}

/** A module whose models take a [Clock]. */
class ClockedModels : ModelProvider {
    override val context = "bank"
    override val fragment = "META-INF/wow/manifest/bank.json"

    override fun models(dependencies: Dependencies): List<AggregateModel<*>> {
        dependencies.get(Clock::class.java)
        return listOf(accountModel(context))
    }
}

/** A module that provides a model of another context than its own. */
class MisplacedModels : ModelProvider {
    override val context = "ledger"
    override val fragment = "META-INF/wow/manifest/ledger.json"

    override fun models(dependencies: Dependencies): List<AggregateModel<*>> = listOf(accountModel("bank"))
}

/** The runtime finds the fragments of a service through the binding index and merges them (decisions D74, D107). */
class ManifestLoaderTest {
    @TempDir
    lateinit var classPath: File

    private val mapper = JsonMapper.builder().build()

    private fun write(path: String, content: ByteArray) {
        File(classPath, path).apply { parentFile.mkdirs() }.writeBytes(content)
    }

    private fun fragment(name: String, manifest: Manifest) =
        write("META-INF/wow/manifest/$name.json", mapper.writeValueAsBytes(ManifestTree.write(manifest)))

    /** A class loader over [classPath] that registers [providers] as the compiler does. */
    private fun classLoader(vararg providers: Class<out ModelProvider>): ClassLoader {
        write(
            "META-INF/services/${ModelProvider::class.java.name}",
            providers.joinToString("\n") { it.name }.toByteArray(),
        )
        return URLClassLoader(arrayOf(classPath.toURI().toURL()), javaClass.classLoader)
    }

    @Test
    fun `the fragments of the registered providers merge into the service's Manifest, which its models match`() {
        fragment("bank", accountManifest(null, "bank"))
        fragment("ledger", accountManifest(null, "ledger"))
        val classLoader = classLoader(BankModels::class.java, LedgerModels::class.java)

        val manifest = ManifestLoader(classLoader).load(ServiceInfo(NODE_SERVICE, "1.0.0"))

        manifest.service.assert().isEqualTo(ServiceInfo(NODE_SERVICE, "1.0.0"))
        manifest.contexts.map { it.name }.assert().containsExactly("bank", "ledger")
        manifest.errors.assert().isEqualTo(FrameworkErrors.ALL)
        val models = ServiceNode.models(Dependencies.NONE, ModelProvider.load(classLoader))
        models.map { it.type.toString() }.assert().containsExactly("bank.account", "ledger.account")
        val node = ServiceNode(
            NODE_SERVICE,
            "node-1",
            "node-1.internal:9090",
            models,
            manifest,
            InMemoryBackends(),
            LoopbackClusterChannel(),
        )
        node.contexts.keys.assert().containsExactly("bank", "ledger")
        ManifestLoader(classLoader).load(NODE_SERVICE).service.assert().isEqualTo(ServiceInfo(NODE_SERVICE))
    }

    @Test
    fun `conflicting fragments fail with every conflict listed`() {
        // Both fragments declare context bank, with its aggregate, command and event.
        fragment("bank", accountManifest(null, "bank"))
        fragment("ledger", accountManifest(null, "bank"))
        val classLoader = classLoader(BankModels::class.java, LedgerModels::class.java)

        val conflicts = assertThrows<ManifestConflictException> { ManifestLoader(classLoader).load(NODE_SERVICE) }
            .conflicts
        conflicts.assert().contains(
            "context bank is declared by META-INF/wow/manifest/bank.json and META-INF/wow/manifest/ledger.json",
        )
        conflicts.any { it.startsWith("command type bank.account.Deposit is declared by") }.assert().isTrue()
        conflicts.any { it.startsWith("event type bank.account.Deposited is declared by") }.assert().isTrue()
    }

    @Test
    fun `fragments that are missing or not a Manifest fail with each one listed`() {
        write("META-INF/wow/manifest/bank.json", "{ not json".toByteArray())
        val classLoader = classLoader(BankModels::class.java, LedgerModels::class.java)

        val problems = assertThrows<ManifestLoadException> { ManifestLoader(classLoader).load(NODE_SERVICE) }.problems

        problems.size.assert().isEqualTo(2)
        problems[0].assert().startsWith("fragment META-INF/wow/manifest/bank.json is not JSON")
        problems[1].assert().isEqualTo("fragment META-INF/wow/manifest/ledger.json is not on the class path")
        write("META-INF/wow/manifest/bank.json", """{"format": 0}""".toByteArray())
        assertThrows<ManifestLoadException> { ManifestLoader(classLoader).read("META-INF/wow/manifest/bank.json") }
            .problems.single().assert().startsWith("fragment META-INF/wow/manifest/bank.json is not a Manifest")
    }

    @Test
    fun `models whose dependency is missing fail the start`() {
        assertThrows<MissingDependencyException> { ServiceNode.models(Dependencies.NONE, listOf(ClockedModels())) }
            .type.assert().isEqualTo(Clock::class.java)
        ServiceNode.models(Dependencies.of(Clock.systemUTC()), listOf(ClockedModels())).size.assert().isEqualTo(1)
    }

    @Test
    fun `a provider's models are of its own context`() {
        assertThrows<IllegalArgumentException> { ServiceNode.models(Dependencies.NONE, listOf(MisplacedModels())) }
            .message.assert().contains("bank.account")
    }
}
