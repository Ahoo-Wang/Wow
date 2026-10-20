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

import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.PeerContracts
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Generates the Kotlin sources of an api module's [contract], `META-INF/wow/contract.json` as
 * [WowPlugin.ASSEMBLE_CONTRACT_TASK] assembles it (decisions D256 ①, D259): per context the object
 * `<api package>.<Context>Contract` ([ContractSources]), which the api module compiles in its
 * [WowPlugin.CONTRACT_SOURCE_SET] source set and its jar carries. No contract, no source.
 */
@CacheableTask
abstract class GenerateContractSources : DefaultTask() {
    /** The api module's assembled contract: one file, or none when no context is the api module's. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val contract: ConfigurableFileCollection

    /** The root of the generated Kotlin sources. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        contract.files.filter { it.isFile }.map(ContractJson::read).flatMap(ContractSources::of).forEach { source ->
            File(output, source.path).apply { parentFile.mkdirs() }.writeText(source.text)
        }
    }
}

/**
 * The Kotlin sources of a contract (decision D259 "契约常量"): per context `<package>.<Context>Contract`, whose
 * package is the one its command and event classes share, holding the context's `NAME` and, per aggregate, an object
 * with the aggregate's `TYPE` and `ref(tenantId, id)`.
 */
internal object ContractSources {
    /** One generated file: its [path] under the source root and its [text]. */
    data class Source(val path: String, val text: String)

    fun of(contract: Manifest): List<Source> = contract.contexts.map(::of)

    fun of(context: ContextManifest): Source {
        val pkg = packageOf(PeerContracts.classesOf(context))
        val name = "${pascal(context.name)}Contract"
        val aggregates = context.aggregates.joinToString("") { aggregate ->
            """
            |
            |    /** The aggregate `${aggregate.name}`. */
            |    object ${pascal(aggregate.name)} {
            |        @JvmField
            |        val TYPE: AggregateType = AggregateType(NAME, "${aggregate.name}")
            |
            |        /** Refers to the instance [id] of `${aggregate.name}` under [tenantId]. */
            |        @JvmStatic
            |        fun ref(tenantId: String, id: String): AggregateRef = TYPE.ref(tenantId, id)
            |    }
            |""".trimMargin()
        }
        val header = if (pkg.isEmpty()) "" else "package $pkg\n\n"
        val text = header +
            """
            |import me.ahoo.wow.api.aggregate.AggregateRef
            |import me.ahoo.wow.api.aggregate.AggregateType
            |
            |/** The context `${context.name}`, generated from its peer contract. Do not edit. */
            |object $name {
            |    const val NAME: String = "${context.name}"
            |""".trimMargin() + aggregates + "}\n"
        val directory = if (pkg.isEmpty()) "" else pkg.replace('.', '/') + "/"
        return Source("$directory$name.kt", text)
    }

    /** The package the binary names [classes] share: the longest common one, empty when they share none. */
    fun packageOf(classes: List<String>): String {
        val packages = classes.map { it.substringBeforeLast('.', "").split('.').filter(String::isNotEmpty) }
        val common = packages.reduceOrNull { shared, next -> shared.zip(next).takeWhile { it.first == it.second }.map { it.first } }
        return common.orEmpty().joinToString(".")
    }

    /** `eshop-inventory` → `EshopInventory`: the name's words, capitalised, without separators. */
    fun pascal(name: String): String = name.split('-', '_', '.', ' ').filter(String::isNotEmpty)
        .joinToString("") { word -> word.replaceFirstChar(Char::uppercaseChar) }
}
