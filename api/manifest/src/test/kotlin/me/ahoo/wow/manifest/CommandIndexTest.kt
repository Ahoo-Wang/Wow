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

/** How the commands of Manifests are named by their classes, on the aggregate a command is sent to. */
class CommandIndexTest {
    class Refresh

    private val schema = Manifest.schemaRef(Refresh::class.java.name)

    private fun aggregate(name: String, commands: List<CommandManifest>) =
        AggregateManifest(name, state = StateManifest(Manifest.schemaRef("audit.$name.State")), commands = commands)

    /** peer.alpha and peer.beta both decide [Refresh]; peer.gamma decides none. */
    private val index = CommandIndex(
        listOf(
            Manifest(
                contexts = listOf(
                    ContextManifest(
                        "peer",
                        aggregates = listOf(
                            aggregate("alpha", listOf(CommandManifest("Refresh", schema = schema))),
                            aggregate("beta", listOf(CommandManifest("Refresh", schema = schema))),
                            aggregate("gamma", emptyList()),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `a command class several aggregates decide is named on each of them`() {
        index.commandTypeOf("peer.alpha", Refresh::class.java).assert().isEqualTo("peer.alpha.Refresh")
        index.commandTypeOf("peer.beta", Refresh::class.java).assert().isEqualTo("peer.beta.Refresh")
        index.aggregatesOf(schema).assert().containsExactly("peer.alpha", "peer.beta")
    }

    @Test
    fun `a class's aggregates are in the order the Manifests list them, and the first names it alone`() {
        fun manifest(context: String, vararg aggregates: String) = Manifest(
            contexts = listOf(
                ContextManifest(
                    context,
                    aggregates = aggregates.map { aggregate(it, listOf(CommandManifest("Refresh", schema = schema))) },
                ),
            ),
        )
        val ordered = CommandIndex(listOf(manifest("peer", "zeta", "beta"), manifest("other", "alpha")))
        ordered.aggregatesOf(schema).assert().containsExactly("peer.zeta", "peer.beta", "other.alpha")
        ordered.commandTypeOf(schema).assert().isEqualTo("peer.zeta.Refresh")
        ordered.commandTypeOf("other.alpha", Refresh::class.java).assert().isEqualTo("other.alpha.Refresh")
    }

    @Test
    fun `an aggregate that decides none of a class names no command of it`() {
        index.commandTypeOf("peer.gamma", Refresh::class.java).assert().isNull()
        index.commandTypeOf("peer.alpha", String::class.java).assert().isNull()
    }
}
