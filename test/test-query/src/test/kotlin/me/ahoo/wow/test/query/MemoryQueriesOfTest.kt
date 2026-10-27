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

package me.ahoo.wow.test.query

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.dsl.listQuery
import me.ahoo.wow.api.read.snapshots
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.test.query.InventoryModels.Companion.STOCK
import me.ahoo.wow.test.query.InventoryModels.Stock
import me.ahoo.wow.test.sagaSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLClassLoader

/** The read side of class paths other than the tests' own: modules whose models load, and fragments that are gone. */
class MemoryQueriesOfTest {
    data class Shelf(val books: Int)

    /** A module whose models build without dependencies: its states' classes come from its models. */
    class LibraryModels : ModelProvider {
        override val binding: Int = ModelProvider.BINDING
        override val context: String = "library"
        override val fragment: String = "META-INF/wow/manifest/test-query-library.json"

        override fun models(dependencies: Dependencies): List<AggregateModel<*>> =
            listOf(aggregate<Shelf>("library", "shelf") { initial { Shelf(0) } })
    }

    /** A module whose fragment is not on the class path. */
    class LostModels : ModelProvider {
        override val binding: Int = ModelProvider.BINDING
        override val context: String = "lost"
        override val fragment: String = "META-INF/wow/manifest/test-query-lost.json"

        override fun models(dependencies: Dependencies): List<AggregateModel<*>> = emptyList()
    }

    /** A class loader over [root] that also sees the tests' classes and resources. */
    private fun loader(root: File, vararg providers: Class<out ModelProvider>): ClassLoader {
        root.resolve("META-INF/services").apply { mkdirs() }.resolve(ModelProvider::class.java.name)
            .writeText(providers.joinToString("\n") { it.name })
        return URLClassLoader(arrayOf(root.toURI().toURL()), javaClass.classLoader)
    }

    @Test
    fun `the state classes of a module whose models load should be its models'`(@TempDir root: File): Unit =
        runBlocking {
            root.resolve("META-INF/wow/manifest").apply { mkdirs() }.resolve("test-query-library.json").writeText(
                """
                {"format":1,"contexts":[{"name":"library","aggregates":[{"name":"shelf",
                "state":{"schema":"#/${'$'}defs/Shelf"},"commands":[],"events":[],"lifecycle":[],
                "queries":[{"model":"snapshot","routes":[]}],"ownership":"TENANT"}],
                "subscriptions":[],"published":[],"errors":[]}],"errors":[],
                "${'$'}defs":{"Shelf":{"type":"object","properties":{"books":{"type":"integer"}}}}}
                """.trimIndent(),
            )
            val queries = MemoryQueries.of(loader(root, LibraryModels::class.java))
            // Found by its state class before any snapshot is given: the model names it.
            queries.trusted().snapshots<Shelf>().list(listQuery { }).toList().assert().isEmpty()
            queries.snapshots(LibraryModels().models(Dependencies.NONE).single().type, "s-1" to Shelf(3))
            queries.of(Caller.of()).snapshots<Shelf>().state("s-1")!!.state.assert().isEqualTo(Shelf(3))
            queries.toString().assert().contains("library.shelf")
        }

    @Test
    fun `a module whose fragment is not on the class path should fail, naming it`(@TempDir root: File) {
        assertThrows<IllegalStateException> { MemoryQueries.of(loader(root, LostModels::class.java)) }
            .message.assert().contains("test-query-lost.json")
    }

    @Test
    fun `the defaults should give the default tenant and the class path's modules`(): Unit = runBlocking {
        val queries = memoryQueries().snapshots(STOCK, "mug" to Stock(2))
        queries.of(Caller.of()).snapshots<Stock>().state("mug")!!.ref.tenantId.assert()
            .isEqualTo(Caller.DEFAULT_TENANT)
        // A Manifest of its own declares no aggregate: any snapshot of one fails.
        assertThrows<IllegalArgumentException> { MemoryQueries(Manifest()).snapshot(STOCK, "mug", Stock(1)) }
        val noop = ProcessorBinding.saga(
            "Probe",
            "onShelf",
            Shelf::class.java
        ) { _: StateEvent<Shelf>, _ -> emptyList() }
        sagaSpec(listOf(noop)) {
            givenSnapshots(STOCK, "mug" to Stock(5)).toString().assert().contains("inventory.stock")
        }
    }
}
