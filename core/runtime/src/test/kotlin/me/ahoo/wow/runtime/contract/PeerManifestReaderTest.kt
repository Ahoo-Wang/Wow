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
import me.ahoo.wow.manifest.PeerManifests
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLClassLoader
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PeerManifestReaderTest {
    private val peersDirectory = File(requireNotNull(javaClass.getResource("/contract/peers/")).toURI())

    /** A jar holding the fixture peers of `contract/peers`, as the Gradle plugin puts them in a module's jar. */
    private fun jar(directory: File, name: String): File = File(directory, name).also { jar ->
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("${PeerManifests.DIRECTORY}/"))
            File(peersDirectory, PeerManifests.DIRECTORY).listFiles()!!.sorted().forEach { file ->
                zip.putNextEntry(ZipEntry("${PeerManifests.DIRECTORY}/${file.name}"))
                zip.write(file.readBytes())
            }
        }
    }

    @Test
    fun `the peers of a directory should be read from beside its index`() {
        val peers = PeerManifestReader(peersLoader("peers")).read()
        peers.map { it.manifest.service?.name }.assert().containsExactly("inventory-service", "payment-service")
        peers.first().source.assert().endsWith("contract/peers/${PeerManifests.resource("inventory")}")
        peers.first().manifest.contexts.single().published.size.assert().isEqualTo(2)
    }

    @Test
    fun `the peers of jars should be read, the same Manifest once`(@TempDir directory: File) {
        val jars = listOf(jar(directory, "a.jar"), jar(directory, "b.jar"))
        val loader = URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), null)
        val peers = PeerManifestReader(loader).read()
        peers.map { it.manifest.service?.name }.assert().containsExactly("inventory-service", "payment-service")
        peers.first().source.assert().startsWith("jar:file:").endsWith("a.jar!/${PeerManifests.resource("inventory")}")
    }

    @Test
    fun `a class path without peers should have none`() {
        PeerManifestReader(URLClassLoader(emptyArray(), null)).read().assert().isEmpty()
    }

    @Test
    fun `every peer that cannot be read should be reported`() {
        val problems = assertThrows<ContractException> { PeerManifestReader(peersLoader("broken")).read() }.problems
        problems.size.assert().isEqualTo(3)
        problems[0].assert().contains("missing.json cannot be read")
        problems[1].assert().contains("malformed.json is not JSON")
        problems[2].assert().contains("format.json is not a Manifest: Manifest format 2 is not supported")
    }
}
