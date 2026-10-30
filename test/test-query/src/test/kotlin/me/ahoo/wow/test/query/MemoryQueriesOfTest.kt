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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.query.dsl.listQuery
import me.ahoo.wow.api.read.QueryApi
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

    data class Loan(val amount: Int)

    data class Lend(val amount: Int)

    /**
     * A module whose model queries, as the compiler generates it for a command function that takes a [QueryApi]: the
     * model factory takes it from the dependencies, so its models build only with one.
     */
    class LendingModels : ModelProvider {
        override val binding: Int = ModelProvider.BINDING
        override val context: String = "lending"
        override val fragment: String = "META-INF/wow/manifest/test-query-lending.json"

        override fun models(dependencies: Dependencies): List<AggregateModel<*>> {
            val queries = dependencies.get(QueryApi::class.java, null, "the Loan model")
            return listOf(
                aggregate<Loan>("lending", "loan") {
                    initial { Loan(0) }
                    decideSuspending<Lend> { _, _ ->
                        queries.of(Caller.of()).snapshots<Loan>()
                        Decision.NoOp
                    }
                },
            )
        }
    }

    data class Desk(val chairs: Int)

    /** The service the office's model takes. */
    class Carpenter(val chairs: Int)

    /**
     * A module whose models take a service, its fragment written by hand: the state's schema is keyed by a plain name,
     * not the class's binary name, so its class comes from the models, which build only with the service.
     */
    class OfficeModels : ModelProvider {
        override val binding: Int = ModelProvider.BINDING
        override val context: String = "office"
        override val fragment: String = "META-INF/wow/manifest/test-query-office.json"

        override fun models(dependencies: Dependencies): List<AggregateModel<*>> {
            val carpenter = dependencies.get(Carpenter::class.java, null, "the Desk model")
            return listOf(aggregate<Desk>("office", "desk") { initial { Desk(carpenter.chairs) } })
        }
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
                "queries":[{"model":"snapshot","routes":[]}],"ownership":"tenant"}],
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
    fun `the state class of a model that takes the QueryApi should be its fragment's, by its binary name (PA-6)`(
        @TempDir root: File,
    ): Unit = runBlocking {
        // As the compiler writes it: the state's schema is keyed by the class's binary name.
        val loan = Loan::class.java.name
        root.resolve("META-INF/wow/manifest").apply { mkdirs() }.resolve("test-query-lending.json").writeText(
            """
            {"format":1,"contexts":[{"name":"lending","aggregates":[{"name":"loan",
            "state":{"schema":"#/${'$'}defs/$loan"},"commands":[],"events":[],"lifecycle":[],
            "queries":[{"model":"snapshot","routes":[]}],"ownership":"tenant"}],
            "subscriptions":[],"published":[],"errors":[]}],"errors":[],
            "${'$'}defs":{"$loan":{"type":"object","properties":{"amount":{"type":"integer"}}}}}
            """.trimIndent(),
        )
        // No dependency is given: the models do not build, and the Manifest names the state class.
        val queries = MemoryQueries.of(loader(root, LendingModels::class.java))
        queries.trusted().snapshots<Loan>().list(listQuery { }).toList().assert().isEmpty()
        queries.snapshots(AggregateType("lending", "loan"), "l-1" to Loan(5))
        queries.of(Caller.of()).snapshots<Loan>().state("l-1")!!.state.assert().isEqualTo(Loan(5))
    }

    @Test
    fun `the state class a hand-written fragment names by a plain key should come from the models the dependencies build`(
        @TempDir root: File,
    ): Unit = runBlocking {
        root.resolve("META-INF/wow/manifest").apply { mkdirs() }.resolve("test-query-office.json").writeText(
            """
            {"format":1,"contexts":[{"name":"office","aggregates":[{"name":"desk",
            "state":{"schema":"#/${'$'}defs/Desk"},"commands":[],"events":[],"lifecycle":[],
            "queries":[{"model":"snapshot","routes":[]}],"ownership":"tenant"}],
            "subscriptions":[],"published":[],"errors":[]}],"errors":[],
            "${'$'}defs":{"Desk":{"type":"object","properties":{"chairs":{"type":"integer"}}}}}
            """.trimIndent(),
        )
        val loader = loader(root, OfficeModels::class.java)
        // With the service, the models build and name the state class.
        MemoryQueries.of(loader, Dependencies.of(Carpenter(4))).trusted().snapshots<Desk>().list(listQuery { })
            .toList().assert().isEmpty()
        // Without it, the type takes its state class from the states a spec gives it.
        val queries = MemoryQueries.of(loader)
        assertThrows<IllegalArgumentException> { queries.trusted().snapshots<Desk>() }
        queries.snapshots(AggregateType("office", "desk"), "d-1" to Desk(2))
        queries.of(Caller.of()).snapshots<Desk>().state("d-1")!!.state.assert().isEqualTo(Desk(2))
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
