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
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.openapi.ManifestOpenApi
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes the OpenAPI 3.1 document of the service's public routes from its merged `manifest.json`, the
 * output of [MergeManifest], for offline tools: the document `GET /wow/openapi` serves, as far as the build knows it.
 * What only the running node knows is the host's: a route without an id it adds for a command a transformer takes,
 * the query models and datasets its backends serve, and the error statuses it configures.
 *
 * `wow-openapi` is plain data like `wow-manifest`, so the task runs in the plugin's own class loader and writes the
 * document with the JSON writer Gradle ships (Groovy's), as [GenerateContract] reads and writes Manifests.
 */
@CacheableTask
internal abstract class GenerateOpenApi : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifestFile: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val manifest = ManifestTree.read(JsonSlurper().parse(manifestFile.get().asFile) as Map<*, *>)
        val json = WRITER.toJson(ManifestOpenApi.write(manifest))
        outputFile.get().asFile.apply { parentFile.mkdirs() }.writeText(JsonOutput.prettyPrint(json, true) + "\n")
    }

    private companion object {
        val WRITER: JsonGenerator = JsonGenerator.Options().disableUnicodeEscaping().build()
    }
}
