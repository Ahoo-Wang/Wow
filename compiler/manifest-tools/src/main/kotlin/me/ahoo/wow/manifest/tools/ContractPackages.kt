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

package me.ahoo.wow.manifest.tools

import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.PeerContracts
import java.io.File
import javax.lang.model.SourceVersion

/**
 * The package of each context's generated `<Context>Contract`: `wow { contractPackage }` of the domain module that
 * declares the context, which other services import, so it is the user's and never derived. The domain module's
 * contract variant carries it beside `contract.json` for the api modules that declare the module, as one
 * `<context>=<package>` line per context. It is a build input of Wow's own: the Gradle plugin's `generateWowContract`
 * writes it, the api module's `assembleWowContract` and `generateWowContractSources` read it, nothing publishes it
 * (the contract jar holds the compiled classes, which a consumer links to) and no other tool reads it, so it has no
 * schema and no version.
 */
internal object ContractPackages {
    /** Writes [packages], by context name, into [file]. */
    fun write(packages: Map<String, String>, file: File) {
        file.parentFile.mkdirs()
        file.writeText(packages.toSortedMap().entries.joinToString("") { (context, pkg) -> "$context=$pkg\n" })
    }

    /** The packages [files] hold, by context name. */
    fun read(files: List<File>): Map<String, String> = files.flatMap { file ->
        file.readLines().filter(String::isNotBlank).map { it.substringBefore('=') to it.substringAfter('=') }
    }.toMap()

    /**
     * The package of the [contexts] of the domain module [module]: [declared], its `wow { contractPackage }`.
     *
     * @throws IllegalStateException when the module has a context and sets none, naming the line to add with the
     * package the contexts' classes share today, or when it is no Java package name (the one check of its value),
     * naming the segment.
     */
    fun of(declared: String?, contexts: List<ContextManifest>, module: String): String? {
        if (contexts.isEmpty()) return declared
        checkNotNull(declared) { missing(contexts, module) }
        problem(declared)?.let { problem ->
            error("wow { contractPackage = \"$declared\" } of project '$module' is not a Java package name: $problem.")
        }
        return declared
    }

    /** What is wrong with [value] as a Java package name: none when it is one, each segment an identifier, no keyword. */
    fun problem(value: String): String? {
        if (value.isEmpty()) return "it is empty"
        value.split('.').forEach { segment ->
            if (!SourceVersion.isIdentifier(segment)) return "its segment \"$segment\" is not a Java identifier"
            if (SourceVersion.isKeyword(segment)) return "its segment \"$segment\" is a Java keyword"
        }
        return null
    }

    private fun missing(contexts: List<ContextManifest>, module: String): String {
        val names = contexts.joinToString(" and ") { "${it.name} (${ContractSources.pascal(it.name)}Contract)" }
        val shared = PeerContracts.packageOf(contexts.flatMap(PeerContracts::classesOf))
        val line = "wow { contractPackage = \"${shared.ifEmpty { "<package>" }}\" }"
        val value = if (shared.isEmpty()) {
            "Its command and event classes share no package, so choose one: $line"
        } else {
            "Add to its build file, with the package its command and event classes share today:\n\n    $line\n"
        }
        return "Project '$module' sets no contractPackage. It is the package of the code generated for its context " +
            "$names, which other services import, so once it is set it should not change. $value"
    }
}
