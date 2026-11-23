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

import com.sun.net.httpserver.HttpServer
import me.ahoo.test.asserts.assert
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The Helm a build renders with ([HelmService]): found only when asked for, fetched once into the Gradle user home and
 * checked against the plugin's digests, a local binary only at the pinned version, and every failure naming
 * [HelmService.MIRROR_PROPERTY] and [HelmService.EXECUTABLE_PROPERTY]. Archives are served by a server of the test.
 */
class HelmServiceTest {
    @TempDir
    lateinit var directory: File

    private val requests = AtomicInteger()
    private val served = mutableMapOf<String, ByteArray>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            requests.incrementAndGet()
            val body = served[exchange.requestURI.path.removePrefix("/")]
            exchange.sendResponseHeaders(if (body == null) 404 else 200, body?.size?.toLong() ?: -1)
            body?.let { exchange.responseBody.use { out -> out.write(it) } }
            exchange.close()
        }
        start()
    }
    private val mirror = "http://127.0.0.1:${server.address.port}/"
    private val project: Project by lazy {
        ProjectBuilder.builder().withProjectDir(
            File(directory, "project")
        ).withGradleUserHomeDir(File(directory, "home"))
            .build()
    }
    private var services = 0

    @AfterEach
    fun stop() = server.stop(0)

    /** A service of the test's own [platform], [digests], cache and properties. */
    private fun service(
        platform: String? = "linux-amd64",
        digests: Map<String, String> = emptyMap(),
        offline: Boolean = false,
        executable: String? = null,
        cache: File = File(directory, "cache"),
    ): HelmService = project.gradle.sharedServices.registerIfAbsent("helm${services++}", HelmService::class.java) {
        it.parameters.cache.set(cache)
        it.parameters.offline.set(offline)
        it.parameters.mirror.set(mirror)
        it.parameters.executable.set(executable)
        it.parameters.platform.set(platform)
        it.parameters.digests.set(digests)
    }.get()

    @Test
    fun `the platform should be Helm's name of the JVM's, and the table should cover six`() {
        HelmRelease.platformOf("Linux", "amd64").assert().isEqualTo("linux-amd64")
        HelmRelease.platformOf("Linux", "aarch64").assert().isEqualTo("linux-arm64")
        HelmRelease.platformOf("Mac OS X", "x86_64").assert().isEqualTo("darwin-amd64")
        HelmRelease.platformOf("Mac OS X", "aarch64").assert().isEqualTo("darwin-arm64")
        HelmRelease.platformOf("Windows 11", "amd64").assert().isEqualTo("windows-amd64")
        HelmRelease.platformOf("Linux", "s390x").assert().isEqualTo("linux-s390x")
        HelmRelease.platformOf("FreeBSD", "amd64").assert().isNull()
        HelmRelease.DIGESTS.keys.assert().containsExactlyInAnyOrder(
            "linux-amd64",
            "linux-arm64",
            "darwin-amd64",
            "darwin-arm64",
            "windows-amd64",
            "windows-arm64",
        )
        HelmRelease.DIGESTS.values.forEach { it.assert().matches("[0-9a-f]{64}") }
        HelmRelease.archiveOf("linux-arm64").assert().isEqualTo("helm-v4.3.1-linux-arm64.tar.gz")
        HelmRelease.archiveOf("windows-arm64").assert().isEqualTo("helm-v4.3.1-windows-arm64.zip")
    }

    @Test
    fun `registering and creating the service should fetch nothing`() {
        HelmService.register(project).get().parameters.let {
            it.cache.get().asFile.assert()
                .isEqualTo(File(directory, "home/wow/helm/${HelmRelease.VERSION}").canonicalFile)
            it.offline.get().assert().isFalse()
            it.platform.orNull.assert()
                .isEqualTo(HelmRelease.platformOf(System.getProperty("os.name"), System.getProperty("os.arch")))
        }
        service(digests = mapOf("linux-amd64" to "0".repeat(64)))
        requests.get().assert().isEqualTo(0)
        File(directory, "home/wow").exists().assert().isFalse()
        File(directory, "cache").exists().assert().isFalse()
    }

    @Test
    fun `a fetched archive of the digest should be kept, and fetched once`() {
        val archive = tarGz("linux-amd64", "helm", SCRIPT)
        served["helm-v4.3.1-linux-amd64.tar.gz"] = archive
        val binary = service(digests = mapOf("linux-amd64" to sha256(archive))).executable()
        binary.assert().isEqualTo(File(directory, "cache/linux-amd64/helm"))
        binary.readText().assert().isEqualTo(SCRIPT)
        binary.canExecute().assert().isTrue()
        // Another build, the same cache: nothing fetched again.
        service(digests = mapOf("linux-amd64" to sha256(archive))).executable().assert().isEqualTo(binary)
        requests.get().assert().isEqualTo(1)
        File(directory, "cache/linux-amd64").list()!!.toList().assert().containsExactly("helm")
    }

    @Test
    fun `a Windows archive should be unzipped`() {
        val archive = zip("windows-amd64", "helm.exe", SCRIPT)
        served["helm-v4.3.1-windows-amd64.zip"] = archive
        service(platform = "windows-amd64", digests = mapOf("windows-amd64" to sha256(archive))).executable()
            .readText().assert().isEqualTo(SCRIPT)
    }

    @Test
    fun `an archive of another digest should be refused, and nothing kept`() {
        val archive = tarGz("linux-amd64", "helm", SCRIPT)
        served["helm-v4.3.1-linux-amd64.tar.gz"] = archive
        val expected = "1".repeat(64)
        assertThrows<GradleException> { service(digests = mapOf("linux-amd64" to expected)).executable() }
            .message.assert().contains(
                "helm-v4.3.1-linux-amd64.tar.gz is not Helm v4.3.1's archive",
                sha256(archive),
                expected,
                HelmService.MIRROR_PROPERTY,
                HelmService.EXECUTABLE_PROPERTY,
            )
        File(directory, "cache/linux-amd64").list()!!.toList().assert().isEmpty()
    }

    @Test
    fun `an archive without the binary, or a mirror that has none, should fail naming the remedy`() {
        val archive = tarGz("linux-amd64", "README.md", "not helm")
        served["helm-v4.3.1-linux-amd64.tar.gz"] = archive
        assertThrows<GradleException> { service(digests = mapOf("linux-amd64" to sha256(archive))).executable() }
            .message.assert().contains("holds no linux-amd64/helm")
        assertThrows<GradleException> {
            service(platform = "darwin-arm64", digests = mapOf("darwin-arm64" to "0".repeat(64))).executable()
        }.message.assert().contains("HTTP 404", HelmService.MIRROR_PROPERTY, HelmService.EXECUTABLE_PROPERTY)
    }

    @Test
    fun `offline without a cached binary should fail naming both properties, and fetch nothing`() {
        assertThrows<GradleException> {
            service(digests = mapOf("linux-amd64" to "0".repeat(64)), offline = true).executable()
        }.message.assert().contains("offline", HelmService.MIRROR_PROPERTY, HelmService.EXECUTABLE_PROPERTY)
        requests.get().assert().isEqualTo(0)
    }

    @Test
    fun `a platform without a digest should be told to set the executable`() {
        listOf(null, "linux-s390x").forEach { platform ->
            assertThrows<GradleException> { service(platform = platform).executable() }
                .message.assert().contains("fetches no Helm for this machine", HelmService.EXECUTABLE_PROPERTY)
        }
    }

    @Test
    fun `a local binary should be used at the pinned version only, and nothing fetched`() {
        val pinned = script("pinned", "v4.3.1")
        service(executable = pinned.path).executable().assert().isEqualTo(pinned)
        val other = script("other", "v3.22.1")
        assertThrows<GradleException> { service(executable = other.path).executable() }
            .message.assert().contains("Helm v3.22.1", "Helm v4.3.1 exactly", HelmService.EXECUTABLE_PROPERTY)
        assertThrows<GradleException> { service(executable = File(directory, "none").path).executable() }
            .message.assert().contains("no executable file")
        val broken = File(directory, "broken").apply {
            writeText("#!/bin/sh\nexit 3\n")
            setExecutable(true)
        }
        assertThrows<GradleException> { service(executable = broken.path).executable() }
            .message.assert().contains("did not answer")
        requests.get().assert().isEqualTo(0)
    }

    private fun script(name: String, version: String): File =
        File(directory, name).apply {
            writeText("#!/bin/sh\necho $version\n")
            setExecutable(true)
        }

    private companion object {
        const val SCRIPT = "#!/bin/sh\necho v4.3.1\n"

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** A gzipped ustar archive as Helm's: the platform's directory, a license, then [name] with [content]. */
        fun tarGz(platform: String, name: String, content: String): ByteArray {
            val tar = ByteArrayOutputStream()
            fun entry(path: String, type: Char, data: ByteArray) {
                val header = ByteArray(512)
                fun put(offset: Int, value: String) = value.toByteArray().copyInto(header, offset)
                put(0, path)
                put(100, "0000755\u0000")
                put(124, "%011o\u0000".format(data.size))
                header[156] = type.code.toByte()
                put(257, "ustar\u000000")
                put(148, "        ")
                put(148, "%06o\u0000 ".format(header.sumOf { it.toInt() and 0xff }))
                tar.write(header)
                tar.write(data)
                tar.write(ByteArray((512 - data.size % 512) % 512))
            }
            entry("$platform/", '5', ByteArray(0))
            entry("$platform/LICENSE", '0', "Apache License 2.0, and some more text.".toByteArray())
            entry("$platform/$name", '0', content.toByteArray())
            tar.write(ByteArray(1024))
            return ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(tar.toByteArray()) } }
                .toByteArray()
        }

        fun zip(platform: String, name: String, content: String): ByteArray =
            ByteArrayOutputStream().also { out ->
                ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(ZipEntry("$platform/LICENSE"))
                    zip.write("Apache License 2.0".toByteArray())
                    zip.putNextEntry(ZipEntry("$platform/$name"))
                    zip.write(content.toByteArray())
                }
            }.toByteArray()
    }
}
