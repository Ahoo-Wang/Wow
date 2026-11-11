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

package me.ahoo.wow.manifest

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * What a reader does with what it would leave out is its caller's to say: a peer's Manifest logs it, one never newer
 * than its reader refuses it ([ManifestTree.REFUSE]); and the format gate takes a whole number only.
 */
class ManifestReaderTest {
    private val manifest = Manifests.fragment()

    /** The tree of [manifest] with every command's `creation` replaced by [creation]. */
    private fun withCreation(creation: String): Map<*, *> = ManifestTree.write(manifest).let { tree ->
        val contexts = (tree["contexts"] as List<*>).map { context ->
            val map = context as Map<*, *>
            map + (
                "aggregates" to (map["aggregates"] as List<*>).map { aggregate ->
                    val fields = aggregate as Map<*, *>
                    fields + ("commands" to (fields["commands"] as List<*>).map { (it as Map<*, *>) + ("creation" to creation) })
                }
                )
        }
        tree + ("contexts" to contexts)
    }

    @Test
    fun `a Manifest never newer than its reader refuses what a lenient read would leave out, naming it`() {
        val refused = assertThrows<IllegalArgumentException> {
            ManifestTree.read(withCreation("sometimes"), ManifestTree.REFUSE)
        }
        refused.message.assert().contains("creation: sometimes is unknown to this reader").contains("refused")
        // A peer's Manifest, read with a sink of its own, leaves the commands out and is told.
        val warnings = mutableListOf<String>()
        ManifestTree.read(withCreation("sometimes")) { warnings += it }
            .contexts.single().aggregates.flatMap { it.commands }.assert().isEmpty()
        warnings.assert().anyMatch { it.contains("is left out") }
    }

    @Test
    fun `a format that is not a whole number an Int holds is refused, not truncated`() {
        listOf(1.5, 4_294_967_297L).forEach { format ->
            assertThrows<IllegalArgumentException> {
                ManifestTree.read(ManifestTree.write(manifest) + ("format" to format), ManifestTree.REFUSE)
            }.message.assert().contains("format is not a whole number: $format")
        }
        // A whole number written as a decimal is the format.
        ManifestTree.read(
            ManifestTree.write(manifest) + ("format" to 1.0),
            ManifestTree.REFUSE
        ).assert().isEqualTo(manifest)
    }
}
