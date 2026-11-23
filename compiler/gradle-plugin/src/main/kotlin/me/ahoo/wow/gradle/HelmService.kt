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

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * The Helm a build renders the `wow-service` chart with, the pinned [HelmRelease.VERSION]. It is found when a task asks
 * for it ([executable]), never when the build is configured, so a build that renders nothing fetches nothing:
 *
 * - [Params.executable] (the Gradle property `wow.helm.executable`): a Helm on this machine, used when it is exactly the
 *   pinned version and refused otherwise. A Helm on the `PATH` is never used: the output must not depend on the machine.
 * - Else the binary cached in the Gradle user home ([Params.cache]), fetched once per platform from
 *   [HelmRelease.BASE_URL], or from [Params.mirror] (`wow.helm.mirror`, a base URL of the same layout), and refused
 *   unless its archive's SHA-256 is the one [HelmRelease.DIGESTS] holds, whatever served it.
 *
 * Offline, a build uses the cached binary and fails without one, naming the two properties; so does a build that cannot
 * fetch it, and one on a platform the plugin fetches no Helm for. One service per build, so a binary is fetched once;
 * builds that share a Gradle user home each move a whole binary into the cache, never a part of one.
 */
internal abstract class HelmService : BuildService<HelmService.Params> {
    interface Params : BuildServiceParameters {
        /** Where the fetched binaries are kept: `<Gradle user home>/wow/helm/<version>`, a directory per platform. */
        val cache: DirectoryProperty

        /** Whether the build runs offline (`--offline`). */
        val offline: Property<Boolean>

        /** A base URL serving Helm's archives as [HelmRelease.BASE_URL] does; absent, that one. */
        val mirror: Property<String>

        /** A Helm binary on this machine; absent, the one fetched. */
        val executable: Property<String>

        /** This machine's platform ([HelmRelease.platformOf]); absent on an operating system the plugin fetches none for. */
        val platform: Property<String>

        /** The archives' digests by platform: [HelmRelease.DIGESTS]. */
        val digests: MapProperty<String, String>
    }

    private var found: File? = null

    /** The Helm to run: found, and fetched if need be, on the first call, then the same. */
    @Synchronized
    fun executable(): File = found ?: (parameters.executable.orNull?.let(::local) ?: cached()).also { found = it }

    private fun local(path: String): File {
        val file = File(path)
        if (!file.canExecute()) {
            throw GradleException("$EXECUTABLE_PROPERTY is $path, which is no executable file.")
        }
        val version = versionOf(file)
        if (version != HelmRelease.VERSION) {
            throw GradleException(
                "$EXECUTABLE_PROPERTY is $path, Helm $version; the plugin renders with Helm ${HelmRelease.VERSION} " +
                    "exactly, so that the manifests do not depend on the machine. Point it at Helm ${HelmRelease.VERSION}, " +
                    "or remove it to let the plugin fetch that version.",
            )
        }
        return file
    }

    private fun versionOf(file: File): String {
        val command = listOf(file.path, "version", "--template", "{{.Version}}")
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (!process.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly()
            throw GradleException("$EXECUTABLE_PROPERTY is ${file.path}, which did not answer \"version\": $output")
        }
        return output
    }

    private fun cached(): File {
        val (platform, digest) = digestOfThisMachine()
        val directory = parameters.cache.dir(platform).get().asFile
        val binary = File(directory, HelmRelease.binaryOf(platform))
        if (binary.isFile) return binary
        val base = (parameters.mirror.orNull ?: HelmRelease.BASE_URL).trimEnd('/')
        val url = "$base/${HelmRelease.archiveOf(platform)}"
        if (parameters.offline.get()) {
            throw GradleException(
                "The build is offline, and Helm ${HelmRelease.VERSION} is not in $directory. ${remedy(url)}",
            )
        }
        fetch(url, platform, digest, binary)
        return binary
    }

    /** This machine's platform and its archive's digest; a failure naming [EXECUTABLE_PROPERTY] when it has none. */
    private fun digestOfThisMachine(): Pair<String, String> {
        val platform = parameters.platform.orNull
        val digest = platform?.let { parameters.digests.get()[it] }
            ?: throw GradleException(
                "The plugin fetches no Helm for this machine (${System.getProperty("os.name")}, " +
                    "${System.getProperty("os.arch")}): set $EXECUTABLE_PROPERTY to Helm ${HelmRelease.VERSION} on it.",
            )
        return platform to digest
    }

    /** Fetches the archive of [platform] from [url], checks it against [digest] and moves its binary to [binary]. */
    private fun fetch(url: String, platform: String, digest: String, binary: File) {
        val directory = binary.parentFile.apply { mkdirs() }.toPath()
        val archive = Files.createTempFile(directory, ".helm-", ".download")
        try {
            download(url, archive)
            val actual = sha256(archive)
            if (actual != digest) {
                throw GradleException(
                    "$url is not Helm ${HelmRelease.VERSION}'s archive: its SHA-256 is $actual, not $digest. " +
                        "Nothing was kept. ${remedy(url)}",
                )
            }
            val extracted = Files.createTempFile(directory, ".helm-", ".binary")
            try {
                extract(archive, platform, extracted)
                extracted.toFile().setExecutable(true)
                // Whole or not at all: a build sharing this cache finds the binary complete, or does not find it.
                Files.move(extracted, binary.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: FileAlreadyExistsException) {
                // Another build moved the same binary in first.
            } finally {
                Files.deleteIfExists(extracted)
            }
        } finally {
            Files.deleteIfExists(archive)
        }
    }

    private fun download(url: String, archive: Path) {
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .build()
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(DOWNLOAD_TIMEOUT_SECONDS))
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofFile(archive))
        } catch (failure: IOException) {
            throw GradleException(
                "Cannot fetch Helm ${HelmRelease.VERSION} from $url: ${failure.message}. ${remedy(url)}",
                failure
            )
        }
        if (response.statusCode() != HTTP_OK) {
            throw GradleException(
                "Cannot fetch Helm ${HelmRelease.VERSION} from $url: HTTP ${response.statusCode()}. ${remedy(url)}",
            )
        }
    }

    private fun remedy(url: String): String =
        "Set $MIRROR_PROPERTY to a mirror of ${HelmRelease.BASE_URL} that serves ${url.substringAfterLast('/')}, or " +
            "$EXECUTABLE_PROPERTY to Helm ${HelmRelease.VERSION} on this machine."

    /** Writes the binary of [platform] (`<platform>/<binary>` in the archive) to [target]. */
    private fun extract(archive: Path, platform: String, target: Path) {
        val entry = "$platform/${HelmRelease.binaryOf(platform)}"
        val zipped = HelmRelease.archiveOf(platform).endsWith(".zip")
        val written = Files.newInputStream(archive).use { input ->
            if (zipped) unzip(input, entry, target) else untar(input, entry, target)
        }
        if (!written) {
            throw GradleException("Helm ${HelmRelease.VERSION}'s archive for $platform holds no $entry.")
        }
    }

    private fun unzip(input: InputStream, entry: String, target: Path): Boolean = ZipInputStream(input).use { zip ->
        generateSequence { zip.nextEntry }.firstOrNull { it.name == entry }?.let {
            Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING)
            true
        } ?: false
    }

    /** Reads a gzipped ustar archive, as Helm's are: a 512-byte header per entry, its content padded to 512 bytes. */
    private fun untar(input: InputStream, entry: String, target: Path): Boolean = GZIPInputStream(input).use { tar ->
        val header = ByteArray(BLOCK)
        while (tar.readNBytes(header, 0, BLOCK) == BLOCK && header[0] != 0.toByte()) {
            val name = field(header, PREFIX_OFFSET, PREFIX_LENGTH).let { prefix ->
                val base = field(header, 0, NAME_LENGTH)
                if (prefix.isEmpty()) base else "$prefix/$base"
            }
            val size = field(header, SIZE_OFFSET, SIZE_LENGTH).ifEmpty { "0" }.toLong(OCTAL)
            if (name == entry && header[TYPE_OFFSET].toInt().toChar() in REGULAR) {
                Files.newOutputStream(target).use { out -> copy(tar, out, size) }
                return@use true
            }
            tar.skipNBytes(size + (BLOCK - size % BLOCK) % BLOCK)
        }
        false
    }

    private fun copy(input: InputStream, output: java.io.OutputStream, size: Long) {
        val buffer = ByteArray(BUFFER)
        var left = size
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(left, BUFFER.toLong()).toInt())
            if (read < 0) throw IOException("The archive ended inside an entry.")
            output.write(buffer, 0, read)
            left -= read
        }
    }

    private fun field(header: ByteArray, offset: Int, length: Int): String =
        String(header, offset, length, Charsets.US_ASCII).substringBefore('\u0000').trim()

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER)
            generateSequence { input.read(buffer).takeIf { it >= 0 } }.forEach { digest.update(buffer, 0, it) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** A mirror of `get.helm.sh`: a base URL serving the same archives. */
        const val MIRROR_PROPERTY = "wow.helm.mirror"

        /** A Helm on this machine, of the pinned version. */
        const val EXECUTABLE_PROPERTY = "wow.helm.executable"

        private const val SERVICE = "wowHelm"
        private const val HTTP_OK = 200
        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val DOWNLOAD_TIMEOUT_SECONDS = 600L
        private const val VERSION_TIMEOUT_SECONDS = 60L
        private const val BLOCK = 512
        private const val BUFFER = 64 * 1024
        private const val NAME_LENGTH = 100
        private const val SIZE_OFFSET = 124
        private const val SIZE_LENGTH = 12
        private const val TYPE_OFFSET = 156
        private const val PREFIX_OFFSET = 345
        private const val PREFIX_LENGTH = 155
        private const val OCTAL = 8
        private val REGULAR = setOf('0', '\u0000')

        /**
         * The build's Helm service, registered once: its parameters read the Gradle properties and the platform, and
         * nothing is fetched until a task calls [executable].
         */
        fun register(project: Project): Provider<HelmService> =
            project.gradle.sharedServices.registerIfAbsent(SERVICE, HelmService::class.java) { spec ->
                spec.parameters.cache.set(File(project.gradle.gradleUserHomeDir, "wow/helm/${HelmRelease.VERSION}"))
                spec.parameters.offline.set(project.gradle.startParameter.isOffline)
                spec.parameters.mirror.set(project.providers.gradleProperty(MIRROR_PROPERTY))
                spec.parameters.executable.set(project.providers.gradleProperty(EXECUTABLE_PROPERTY))
                spec.parameters.platform.set(
                    HelmRelease.platformOf(System.getProperty("os.name"), System.getProperty("os.arch")),
                )
                spec.parameters.digests.set(HelmRelease.DIGESTS)
            }
    }
}
