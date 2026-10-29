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

import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.copyWith
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

/**
 * A service's committed Manifest baseline: its Manifest without its version, which moves with every release, and the
 * compatibility [line] it was recorded on, the major version of the service then, which moves once per major.
 *
 * Within its line the contract only grows: a breaking change needs the service's next major version
 * ([allowsBreaking]). A pre-release of a major's first version (`11.0.0-alpha.1`, Wow's own `10.0.0-beta.2`) keeps the
 * line open: until that major's first release nothing is promised, so it may break.
 */
internal data class Baseline(val manifest: Manifest, val line: Int?) {
    companion object {
        /** The baseline's property of its line, beside the Manifest's own. */
        const val LINE = "line"

        private val mapper = jacksonObjectMapper()
        private val OPEN = Regex("""\d+\.0\.0-.+""")

        fun read(file: File): Baseline {
            val tree = mapper.readValue(file, Map::class.java)
            return Baseline(ManifestTree.read(tree), (tree[LINE] as? Number)?.toInt())
        }

        /** [manifest] as a baseline records it: without its service's version. */
        fun of(manifest: Manifest, line: Int?): Baseline =
            Baseline(manifest.copyWith(service = manifest.service?.copyWith(version = null)), line)

        /** The major version of [version] (`10` of `10.1.0`), null for none or one that is no number. */
        fun majorOf(version: String?): Int? = version?.substringBefore('.')?.toIntOrNull()

        /** Whether [version] is a pre-release of a major's first version, which keeps the line open. */
        fun opens(version: String?): Boolean = version != null && OPEN.matches(version)

        /**
         * Whether a service at [version] may break the contract of the [line]: it is of a later major, or a pre-release
         * of a major's first version.
         */
        fun allowsBreaking(line: Int?, version: String?): Boolean {
            if (opens(version)) return true
            val major = majorOf(version) ?: return false
            return line != null && major > line
        }
    }

    fun write(file: File) {
        val tree = LinkedHashMap<String, Any?>()
        ManifestTree.write(manifest).forEach { (key, value) ->
            tree[key] = value
            if (key == "format" && line != null) tree[LINE] = line
        }
        file.parentFile.mkdirs()
        file.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree) + "\n")
    }
}
