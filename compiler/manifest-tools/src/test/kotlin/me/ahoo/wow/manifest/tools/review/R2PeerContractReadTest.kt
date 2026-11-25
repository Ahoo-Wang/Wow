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

package me.ahoo.wow.manifest.tools.review

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.Manifest.Companion.schemaRef
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.PeerContracts
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.tools.ManifestJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

/**
 * Review r2 (the Manifest): a service's build reads each peer api jar's `META-INF/wow/contract.json`
 * (`ManifestJson.contract`, used by `ManifestTools.kt:68` and `:387`) with `ManifestTree.REFUSE`, the policy of "a
 * Manifest never newer than its reader". A peer's contract is not that: services on adjacent minor versions use each
 * other's contract jars (AGENTS.md, Binary Compatibility; D48), and `operate/upgrades.md` says a Manifest reader older
 * than its writer "leaves out what an unknown value belongs to ... and logs it". The node reads the same file so
 * (`ClassPathContracts.load`); the build refuses it, so a 10.0 service cannot build against the api jar of a 10.1 peer
 * whose contract carries a value 10.1 added.
 */
class R2PeerContractReadTest {
    class CartState(val id: String)

    @TempDir
    lateinit var directory: File

    @Test
    fun `a peer's contract holding a value a newer writer added should be read leniently by the build`() {
        val contract = PeerContracts.contractOf(
            listOf(
                Manifest(
                    contexts = listOf(
                        ContextManifest(
                            name = "billing",
                            aggregates = listOf(
                                AggregateManifest(name = "invoice", state = StateManifest(schemaRef(CartState::class.java.name))),
                                AggregateManifest(name = "payment", state = StateManifest(schemaRef(CartState::class.java.name))),
                            ),
                        ),
                    ),
                ),
            ),
            "billing-service",
        )
        val mapper = jacksonObjectMapper()
        // A 10.1 writer's contract: the aggregate `invoice` of an ownership this reader does not know.
        @Suppress("UNCHECKED_CAST")
        val tree = mapper.readValue(mapper.writeValueAsString(ManifestTree.write(contract)), Map::class.java)
            as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val aggregates = ((tree["contexts"] as List<Map<String, Any?>>).single()["aggregates"])
            as List<MutableMap<String, Any?>>
        aggregates.first()["ownership"] = "delegated"
        File(directory, PeerContracts.RESOURCE).apply { parentFile.mkdirs() }.writeText(mapper.writeValueAsString(tree))

        val read = checkNotNull(ManifestJson.contract(directory)).manifest
        // As a node reads it: the aggregate of the unknown value left out, the rest read.
        read.contexts.single().aggregates.map { it.name }.assert().containsExactly("payment")
    }
}
