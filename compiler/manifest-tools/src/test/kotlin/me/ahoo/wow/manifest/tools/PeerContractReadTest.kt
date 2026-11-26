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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.Manifest.Companion.schemaRef
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.PeerContracts
import me.ahoo.wow.manifest.StateManifest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.util.function.Consumer

/**
 * A peer's contract on the build's class path (#7043): services on adjacent minor versions use each other's contract
 * jars, so the build reads a contract as a node reads it, leaving out what a value of a newer Wow belongs to and
 * telling it, instead of refusing the build.
 */
class PeerContractReadTest {
    class InvoiceState(val id: String)

    class CartState(val id: String)

    @TempDir
    lateinit var directory: File

    private val mapper = jacksonObjectMapper()

    private fun aggregate(name: String, state: Class<*>) =
        AggregateManifest(name = name, state = StateManifest(schemaRef(state.name)))

    /**
     * A class path directory holding the billing peer's contract as a 10.1 writer wrote it: its aggregate `invoice` of
     * an ownership this reader does not know, beside its classes.
     */
    private fun newerContract(): File = File(directory, "billing-api").also { entry ->
        val contract = PeerContracts.contractOf(
            listOf(
                Manifest(
                    contexts = listOf(
                        ContextManifest(
                            name = "billing",
                            aggregates = listOf(
                                aggregate("invoice", InvoiceState::class.java),
                                aggregate("payment", InvoiceState::class.java),
                            ),
                        ),
                    ),
                ),
            ),
            "billing-service",
        )

        @Suppress("UNCHECKED_CAST")
        val tree = mapper.readValue(mapper.writeValueAsString(ManifestTree.write(contract)), Map::class.java)
            as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val aggregates = (tree["contexts"] as List<Map<String, Any?>>).single()["aggregates"]
            as List<MutableMap<String, Any?>>
        aggregates.first()["ownership"] = "delegated"
        File(entry, PeerContracts.RESOURCE).apply { parentFile.mkdirs() }.writeText(mapper.writeValueAsString(tree))
        contract.contexts.flatMap(PeerContracts::classesOf).distinct().forEach { binary ->
            File(entry, binary.replace('.', '/') + ".class").apply { parentFile.mkdirs() }.writeBytes(ByteArray(0))
        }
    }

    @Test
    fun `a peer's contract holding a value a newer writer added should be read leniently, naming the contract`() {
        val warnings = mutableListOf<String>()
        val read = checkNotNull(ManifestJson.contract(newerContract(), warnings::add)).manifest
        read.contexts.single().aggregates.map { it.name }.assert().containsExactly("payment")
        warnings.assert().anyMatch { it.startsWith("contract ") && it.contains("ownership: delegated is unknown") }
    }

    @Test
    fun `merging should take such a contract, and tell what it left out`() {
        val own = File(directory, "classes").also { classes ->
            val shop = Manifest(
                contexts = listOf(
                    ContextManifest(name = "shop", aggregates = listOf(aggregate("cart", CartState::class.java)))
                ),
                defs = mapOf(CartState::class.java.name to mapOf("type" to "object")),
            )
            ManifestJson.write(shop, File(classes, "${Manifest.FRAGMENT_DIRECTORY}/shop/domain.json"))
        }
        val warnings = mutableListOf<String>()
        val output = File(directory, "manifest.json")

        ManifestTools.merge(
            listOf(own, newerContract()),
            emptyMap(),
            "shop-service",
            "fallback",
            null,
            output,
            Consumer { warnings += it },
        ).assert().isEqualTo(1)

        ManifestJson.read(output).contexts.map { it.name }.assert().containsExactly("shop")
        warnings.assert().anyMatch { it.contains("ownership: delegated is unknown") }
    }
}
