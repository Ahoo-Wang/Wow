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

package me.ahoo.wow.migrate

import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.ZoneId
import java.util.HexFormat
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * What a comparison's result depends on besides the two stores, as one SHA-256, so a comparison again
 * ([StateComparison]) knows the result it recorded still holds for every instance unchanged on both sides:
 *
 * - the map and the Manifests the configuration names, and the files of its classpath (the V10 models, their state
 *   and their upgraders), by their content: a path that does not exist counts as such;
 * - the sample, which picks the instances compared;
 * - the time zone, in which a state that turns a date into an instant with the system's zone rebuilds;
 * - the tool's own code ([tool]): the directory of the jar it runs from, its dependencies, or the directory of its
 *   classes.
 *
 * A directory counts by the relative path and the content of each file under it, so the same files elsewhere give the
 * same fingerprint.
 */
internal object ComparisonFingerprint {
    fun of(config: MigrationConfig, sample: Int, zone: ZoneId = ZoneId.systemDefault(), tool: Path? = tool()): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.text("sample $sample")
        digest.text("zone ${zone.id}")
        (listOf(config.map) + config.manifests + config.classpath).forEach { digest.path(Path.of(it)) }
        tool?.let { digest.path(it) }
        return HexFormat.of().formatHex(digest.digest())
    }

    /** Where the tool's code is: the directory of its jar (its dependencies beside it), or its classes. */
    private fun tool(): Path? = ComparisonFingerprint::class.java.protectionDomain?.codeSource?.location
        ?.let { Path.of(it.toURI()) }
        ?.let { if (it.isRegularFile()) it.parent else it }

    private fun MessageDigest.text(value: String) {
        update(value.toByteArray(Charsets.UTF_8))
        update(0)
    }

    private fun MessageDigest.path(root: Path) {
        when {
            root.isDirectory() -> Files.walk(root).use { paths ->
                paths.filter { it.isRegularFile() }.sorted().forEach { file ->
                    text(root.relativize(file).joinToString("/"))
                    file(file)
                }
            }

            root.isRegularFile() -> {
                text(root.fileName.toString())
                file(root)
            }

            else -> text("missing ${root.fileName}")
        }
    }

    private fun MessageDigest.file(file: Path) {
        DigestInputStream(Files.newInputStream(file), this).use { it.transferTo(OutputStream.nullOutputStream()) }
        update(0)
    }
}
