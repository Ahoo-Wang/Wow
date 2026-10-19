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
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Writes the peer contract of the domain module's contexts (decision D234): its completed Manifest fragments
 * ([fragments], the output of [GenerateManifestFragment]) filtered down to what another service reads
 * ([PeerContracts.contractOf]), with no service. The module offers it as the variant `wowContractElements`, and the
 * api modules it declares in `api(...)` package it ([AssembleContract]).
 *
 * The Manifests are plain JSON trees ([ManifestTree]), read and written with the JSON library Gradle ships (Groovy's),
 * so no worker is needed.
 */
@CacheableTask
abstract class GenerateContract : DefaultTask() {
    /** The module's completed fragments, `META-INF/wow/manifest/<module>.json`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val fragments: ConfigurableFileCollection

    /** The contract, a Manifest without a service; with no context when the module has no fragment. */
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val fragments = fragments.files.sortedBy { it.name }.map(ContractJson::read)
        ContractJson.write(PeerContracts.contractOf(fragments), outputFile.get().asFile)
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
