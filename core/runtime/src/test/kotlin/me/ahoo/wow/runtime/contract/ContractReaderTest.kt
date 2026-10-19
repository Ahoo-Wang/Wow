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

package me.ahoo.wow.runtime.contract

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.PeerContracts
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.Collections
import java.util.Enumeration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ContractReaderTest {
    /** An api jar holding the fixture contract of `contract/<name>`, as the Gradle plugin packages it. */
    private fun jar(directory: File, name: String, fixture: String): File = File(directory, name).also { jar ->
        val contract = requireNotNull(javaClass.getResource("/contract/$fixture/${PeerContracts.RESOURCE}"))
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(PeerContracts.RESOURCE))
            zip.write(contract.readBytes())
        }
    }

    @Test
    fun `the contract of each directory should be read`() {
        val contracts = ContractReader(contractsLoader("inventory", "payment")).read()
        contracts.map { it.manifest.contexts.single().name }.assert().containsExactly("inventory", "payment")
        contracts.first().source.assert().endsWith("contract/inventory/${PeerContracts.RESOURCE}")
        contracts.first().manifest.service.assert().isNull()
        contracts.first().manifest.contexts.single().published.size.assert().isEqualTo(2)
    }

    @Test
    fun `the contracts of jars should be read, the same contract once`(@TempDir directory: File) {
        val jars = listOf(
            jar(directory, "inventory-api.jar", "inventory"),
            jar(directory, "copy.jar", "inventory"),
            jar(directory, "payment-api.jar", "payment"),
        )
        val loader = URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), null)
        val contracts = ContractReader(loader).read()
        contracts.map { it.manifest.contexts.single().name }.assert().containsExactly("inventory", "payment")
        contracts.first().source.assert().startsWith("jar:file:")
            .endsWith("inventory-api.jar!/${PeerContracts.RESOURCE}")
    }

    @Test
    fun `a class path without contracts should have none`() {
        ContractReader(URLClassLoader(emptyArray(), null)).read().assert().isEmpty()
    }

    @Test
    fun `every contract that cannot be read should be reported`(@TempDir directory: File) {
        val missing = File(directory, "missing.json").toURI().toURL()
        val loader = object : ClassLoader(contractsLoader("malformed", "format")) {
            override fun getResources(name: String): Enumeration<URL> =
                Collections.enumeration(parent.getResources(name).toList() + missing)
        }
        val problems = assertThrows<ContractException> { ContractReader(loader).read() }.problems
        problems.size.assert().isEqualTo(3)
        problems[0].assert().contains("malformed/${PeerContracts.RESOURCE} is not JSON")
        problems[1].assert().contains("format/${PeerContracts.RESOURCE} is not a Manifest: Manifest format 2")
        problems[2].assert().contains("missing.json cannot be read")
    }
}
