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

import groovy.json.JsonGenerator
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.PeerContracts
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.ZipFile

/**
 * Writes the peer contract of the domain module's contexts: its completed Manifest fragments
 * ([fragments], the output of [GenerateManifestFragment]) filtered down to what another service reads
 * ([PeerContracts.contractOf]), naming the service that hosts them when the module declares it ([service]). The module
 * offers it as the variant `wowContractElements`, and the api modules that declare the module in `wowContract(...)`
 * package it ([AssembleContract]).
 *
 * A context whose command or event classes are in a project of the module's class path that does not pair with it
 * ([unpaired]: no `wowContract(project("<module>"))` there, or no `me.ahoo.wow.api`) fails the task, naming the line to
 * add: the contract would reach no api module, and no peer would see the context.
 *
 * The Manifests are plain JSON trees ([ManifestTree]), read and written with the JSON library Gradle ships (Groovy's),
 * so no worker is needed.
 */
@CacheableTask
internal abstract class GenerateContract : DefaultTask() {
    /** The module's completed fragments, `META-INF/wow/manifest/<module>.json`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val fragments: ConfigurableFileCollection

    /**
     * The service that hosts the module's contexts (`wow { service = "…" }`), which a peer
     * finds by its name; none when the module does not declare it.
     */
    @get:Input
    @get:Optional
    abstract val service: Property<String>

    /** The module's project path, which a failure names. */
    @get:Input
    abstract val module: Property<String>

    /** The class path entries of projects that do not pair with the module, each to its project path. */
    @get:Internal
    abstract val unpaired: MapProperty<String, String>

    /** The projects of [unpaired], whose pairing changes the outcome. */
    @get:Input
    abstract val unpairedProjects: ListProperty<String>

    /** The contract, a Manifest that names [service] alone; with no context when the module has no fragment. */
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val fragments = fragments.files.sortedBy { it.name }.map(ContractJson::read)
        val contract = PeerContracts.contractOf(fragments, service.orNull)
        val problems = contract.contexts.flatMap { context ->
            PeerContracts.classesOf(context).mapNotNull { binary -> unpairedOwnerOf(binary)?.let { binary to it } }
                .groupBy({ it.second }, { it.first }).map { (api, classes) ->
                    "context ${context.name} has its classes ${classes.sorted().joinToString()} in project '$api', which " +
                        "does not pair with project '${module.get()}': in $api's build file, apply " +
                        "id(\"${WowPlugin.API_ID}\") and add dependencies { wowContract(project(\"${module.get()}\")) }"
                }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "The contract of project '${module.get()}' reaches no api module:\n" +
                    problems.joinToString("\n") { "- $it" },
            )
        }
        ContractJson.write(contract, outputFile.get().asFile)
    }

    /** The project of [unpaired] whose classes hold the class [binary], or null. */
    private fun unpairedOwnerOf(binary: String): String? {
        val path = binary.replace('.', '/') + ".class"
        return unpaired.get().entries.firstOrNull { (entry, _) ->
            val file = File(entry)
            when {
                file.isDirectory -> File(file, path).isFile
                file.isFile && file.name.endsWith(".jar") -> ZipFile(file).use { it.getEntry(path) != null }
                else -> false
            }
        }?.value
    }
}

/** Reads and writes Manifests as JSON with Groovy's JSON library, which Gradle ships. */
internal object ContractJson {
    private val WRITER: JsonGenerator = JsonGenerator.Options().disableUnicodeEscaping().build()

    fun read(file: File): Manifest = ManifestTree.read(JsonSlurper().parse(file) as Map<*, *>)

    fun write(manifest: Manifest, file: File) {
        file.parentFile.mkdirs()
        file.writeText(JsonOutput.prettyPrint(WRITER.toJson(ManifestTree.write(manifest)), true) + "\n")
    }
}
