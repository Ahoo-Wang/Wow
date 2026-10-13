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

import groovy.json.JsonSlurper
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.PeerManifests
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.ZipFile

/**
 * Copies the merged Manifest of each peer service ([peers], the `wowPeers` configuration) into the module's resources
 * at `META-INF/wow/peers/<service>.json`, with the index beside them ([PeerManifests], decision D139), for the
 * runtime's contract directory.
 *
 * The build fails, listing every conflict ([PeerManifests.conflicts]), when a file is not a service's merged
 * Manifest, when two peers are of one service or declare one context, or when a peer declares a context of this
 * service: one of the Manifest fragments on [classpath], the module's own and those of its dependencies.
 *
 * The Manifests are plain JSON trees ([ManifestTree]), read with the JSON reader Gradle ships (Groovy's), so no
 * worker is needed; each is copied byte for byte.
 */
@CacheableTask
abstract class CollectPeerManifests : DefaultTask() {
    /** The peers' merged `manifest.json` files. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val peers: ConfigurableFileCollection

    /** Where this service's Manifest fragments are: the module's completed fragment and its runtime class path. */
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    /** This service's name, which no peer takes. */
    @get:Input
    abstract val serviceName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun collect() {
        val problems = mutableListOf<String>()
        val peers = peers.files.sorted().mapNotNull { file ->
            read(file.path, file.readBytes(), problems)?.let { file to it }
        }
        val local = classpath.files.flatMap { fragments(it, problems) }.flatMap { it.contexts }.map { it.name }
        problems += PeerManifests.conflicts(serviceName.get(), local, peers.map { it.second })
        if (problems.isNotEmpty()) {
            throw GradleException("The peer Manifests cannot be used:\n" + problems.joinToString("\n") { "- $it" })
        }
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        if (peers.isEmpty()) {
            return
        }
        val directory = File(output, PeerManifests.DIRECTORY).apply { mkdirs() }
        val names = peers.map { (file, peer) ->
            val name = PeerManifests.fileName(peer.manifest.service!!.name)
            file.copyTo(File(directory, name))
            name
        }.sorted()
        File(output, PeerManifests.INDEX).writeText(names.joinToString("\n", postfix = "\n"))
    }

    /** The Manifest fragments of a class path entry, a directory or a jar. */
    private fun fragments(entry: File, problems: MutableList<String>): List<Manifest> = when {
        entry.isDirectory -> File(entry, Manifest.FRAGMENT_DIRECTORY).listFiles { file -> file.extension == "json" }
            .orEmpty().sorted().mapNotNull { read(it.path, it.readBytes(), problems)?.manifest }

        entry.isFile && entry.extension == "jar" -> ZipFile(entry).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && isFragment(it.name) }
                .sortedBy { it.name }
                .mapNotNull { read("$entry!/${it.name}", zip.getInputStream(it).readBytes(), problems)?.manifest }
                .toList()
        }

        else -> emptyList()
    }

    private fun isFragment(name: String): Boolean =
        name.startsWith("${Manifest.FRAGMENT_DIRECTORY}/") && name.endsWith(".json") &&
            '/' !in name.removePrefix("${Manifest.FRAGMENT_DIRECTORY}/")

    @Suppress("TooGenericExceptionCaught") // Groovy's reader fails with its own exceptions on malformed JSON.
    private fun read(source: String, bytes: ByteArray, problems: MutableList<String>): ManifestFragment? = try {
        val tree = JsonSlurper().parse(bytes)
        if (tree is Map<*, *>) {
            ManifestFragment(source, ManifestTree.read(tree))
        } else {
            problems += "$source is not a JSON object"
            null
        }
    } catch (failure: RuntimeException) {
        problems += "$source is not a Manifest: ${failure.message}"
        null
    }
}
