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

package me.ahoo.wow.compiler.model

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import me.ahoo.test.asserts.assert
import me.ahoo.wow.compiler.model.source.QueryPropertiesSource
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test

/**
 * The compiler generates `<State>Properties` for each aggregate's state when the module has `wow-query-api`, which
 * every module has through `wow-api` (decision D260): these tests compile against the real `SnapshotProperties`.
 */
@OptIn(ExperimentalCompilerApi::class)
class QueryPropertiesTest {
    private val order = SourceFile.kotlin(
        "Order.kt",
        """
        package sample

        import com.fasterxml.jackson.annotation.JsonIgnore
        import com.fasterxml.jackson.annotation.JsonIgnoreProperties
        import com.fasterxml.jackson.annotation.JsonProperty
        import me.ahoo.wow.api.annotation.AggregateRoot

        data class OrderPlaced(val customerId: String)
        enum class Status { NEW, PAID }
        @JvmInline value class Sku(val value: String)
        data class Line(val sku: Sku, val quantity: Int, val parent: Line? = null)
        data class Address(val city: String, @JsonProperty("zip") val postalCode: String)
        typealias Lines = List<Line>

        @AggregateRoot
        class Order(private val state: OrderState) {
            fun onCommand(command: PlaceOrder): OrderPlaced = OrderPlaced(command.customerId)
        }

        data class PlaceOrder(val customerId: String)

        @JsonIgnoreProperties("draft")
        class OrderState(val id: String) {
            var customerId: String? = null
                private set
            var status: Status = Status.NEW
            var address: Address? = null
            var lines: Lines = emptyList()
            var attributes: Map<String, String> = emptyMap()
            var tags: Set<String> = emptySet()
            var totalURLCount: Int = 0
            @JsonIgnore
            var secret: String = ""
            var draft: String = ""
            private var hidden: Int = 0
            internal var local: Int = 0
            val computed: Int get() = 1
            // A member extension property takes a receiver: Jackson writes no property of it.
            val Int.doubled: Int get() = this * 2

            fun onSourcing(event: OrderPlaced) { customerId = event.customerId }
        }

        @AggregateRoot
        class Ledger(val id: String) {
            var balance: Int = 0
            fun onCommand(command: Open): Opened = Opened(1)
            fun onSourcing(event: Opened) { balance = event.amount }
        }
        data class Open(val note: String)
        data class Opened(val amount: Int)
        """.trimIndent(),
    )

    @Test
    fun `a state's properties are its serialized paths under state`() {
        val (_, result) = compileModel("generate", order)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        result.properties("sample.OrderStateProperties").assert().isEqualTo(
            linkedMapOf(
                "STATE__ID" to "state.id",
                "STATE__CUSTOMER_ID" to "state.customerId",
                "STATE__STATUS" to "state.status",
                "STATE__ADDRESS" to "state.address",
                "STATE__ADDRESS__CITY" to "state.address.city",
                "STATE__ADDRESS__ZIP" to "state.address.zip",
                "STATE__LINES" to "state.lines",
                "STATE__LINES__SKU" to "state.lines.sku",
                "STATE__LINES__QUANTITY" to "state.lines.quantity",
                "STATE__LINES__PARENT" to "state.lines.parent",
                "STATE__ATTRIBUTES" to "state.attributes",
                "STATE__TAGS" to "state.tags",
                "STATE__TOTAL_URL_COUNT" to "state.totalURLCount",
                // A computed property Jackson writes into the snapshot: read-only, and queried as any other.
                "STATE__COMPUTED" to "state.computed",
            ),
        )
    }

    @Test
    fun `each constant should be typed by its property, so a comparison of another type does not compile`() {
        val typed = SourceFile.kotlin(
            "Queries.kt",
            """
            package sample

            import me.ahoo.wow.api.query.dsl.listQuery

            val query = listQuery {
                filter {
                    OrderStateProperties.STATE__TOTAL_URL_COUNT gte 3
                    OrderStateProperties.STATE__STATUS eq Status.PAID
                    OrderStateProperties.STATE__CUSTOMER_ID eq null
                    OrderStateProperties.STATE__LINES__QUANTITY isIn listOf(1, 2)
                    OrderStateProperties.VERSION between 1..3
                }
            }
            """.trimIndent(),
        )
        val (_, compiled) = compileModel("generate", order, typed)
        compiled.exitCode.assert().withFailMessage { compiled.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        listOf(
            "OrderStateProperties.STATE__TOTAL_URL_COUNT eq \"three\"",
            "OrderStateProperties.STATE__STATUS eq \"PAID\"",
            // A field the state never leaves null is not compared with null.
            "OrderStateProperties.STATE__ID eq null",
        ).forEach { condition ->
            val mistyped = SourceFile.kotlin(
                "Mistyped.kt",
                """
                package sample

                import me.ahoo.wow.api.query.dsl.listQuery

                val query = listQuery { filter { $condition } }
                """.trimIndent(),
            )
            val (_, refused) = compileModel("generate", order, mistyped)
            refused.exitCode.assert().describedAs(condition).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
            refused.messages.assert().contains("Mistyped.kt")
        }
    }

    @Test
    fun `a root that is its own state gets them too`() {
        val (_, result) = compileModel("generate", order)
        result.properties("sample.LedgerProperties").assert().isEqualTo(
            linkedMapOf("STATE__ID" to "state.id", "STATE__BALANCE" to "state.balance"),
        )
    }

    @Test
    fun `a state without an id has only its own fields beside the snapshot's (D203)`() {
        val tally = SourceFile.kotlin(
            "Tally.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot

            data class Count(val by: Int)
            data class Counted(val by: Int)

            @AggregateRoot
            class Tally(private val state: TallyState) {
                fun onCommand(command: Count): Counted = Counted(command.by)
            }

            class TallyState {
                var count: Int = 0
                fun onSourcing(event: Counted) { count += event.by }
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", tally)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        // The aggregate's id is a system field of the snapshot record, not of the state.
        result.properties("sample.TallyStateProperties").assert().isEqualTo(
            linkedMapOf("STATE__COUNT" to "state.count"),
        )
    }

    @Test
    fun `an interface-typed property should name its getters' paths, as the schema does, other shapes none`() {
        val labeled = SourceFile.kotlin(
            "Shelf.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot

            data class Stock(val by: Int)
            data class Stocked(val by: Int)

            interface Keyed {
                val code: String
                val key: String get() = "k-${'$'}code"
            }
            data class Label(override val code: String) : Keyed
            sealed interface Mark
            data class Dot(val size: Int) : Mark
            abstract class Shape { val sides: Int = 0 }
            class Square : Shape()
            sealed class Kind { data object Plain : Kind() }

            @AggregateRoot
            class Shelf(private val state: ShelfState) {
                fun onCommand(command: Stock): Stocked = Stocked(command.by)
            }

            class ShelfState {
                var keyed: Keyed = Label("a")
                var mark: Mark = Dot(1)
                var shape: Shape = Square()
                var kind: Kind = Kind.Plain
                var at: java.time.Instant = java.time.Instant.EPOCH
                var due: javax.xml.datatype.Duration? = null
                fun onSourcing(event: Stocked) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", labeled)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        result.properties("sample.ShelfStateProperties").assert().isEqualTo(
            linkedMapOf(
                "STATE__KEYED" to "state.keyed",
                "STATE__KEYED__CODE" to "state.keyed.code",
                "STATE__KEYED__KEY" to "state.keyed.key",
                "STATE__MARK" to "state.mark",
                "STATE__SHAPE" to "state.shape",
                "STATE__KIND" to "state.kind",
                "STATE__AT" to "state.at",
                "STATE__DUE" to "state.due",
            ),
        )
    }

    @Test
    fun `an internal state's query fields should be internal, so a legal internal domain compiles`() {
        val domain = SourceFile.kotlin(
            "Counter.kt",
            """
            package sample.visibility

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.CreateAggregate

            @CreateAggregate data class Start(val value: String)
            data class Started(val value: String)
            internal enum class Status { NEW, ACTIVE }
            enum class Level { LOW, HIGH }

            @AggregateRoot internal class Counter(private val state: CounterState) {
                fun onCommand(command: Start): Started = Started(command.value)
            }
            internal class CounterState {
                var status: Status = Status.NEW
                var history: List<Status> = emptyList()
                fun onSourcing(event: Started) { status = Status.ACTIVE }
            }

            @AggregateRoot class Gauge(private val state: GaugeState) {
                fun onCommand(command: Start): Started = Started(command.value)
            }
            class GaugeState {
                internal var status: Status = Status.NEW
                var level: Level = Level.LOW
                fun onSourcing(event: Started) = Unit
            }
            """.trimIndent(),
        )
        // The module's own code reads them, as it reads its state.
        val queries = SourceFile.kotlin(
            "Queries.kt",
            """
            package sample.visibility

            import me.ahoo.wow.api.query.dsl.listQuery

            internal val counters = listQuery {
                filter {
                    CounterStateProperties.STATE__STATUS eq Status.ACTIVE
                    GaugeStateProperties.STATE__LEVEL eq Level.HIGH
                }
            }
            """.trimIndent(),
        )
        val (compilation, result) = compileModel("generate", domain, queries)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        compilation.generated("CounterStateProperties.kt").assert()
            .contains("internal object CounterStateProperties")
            .contains("public val STATE__STATUS: me.ahoo.wow.api.query.QueryField<sample.visibility.Status>")
        // A public state's object stays public: its public fields are of public types, an internal one is no path.
        compilation.generated("GaugeStateProperties.kt").assert()
            .contains("public object GaugeStateProperties")
            .contains("public val STATE__LEVEL: me.ahoo.wow.api.query.QueryField<sample.visibility.Level>")
            .doesNotContain("STATE__STATUS")
    }

    @Test
    fun `a public root built from an internal state should get an internal model and query fields`() {
        val domain = SourceFile.kotlin(
            "Meter.kt",
            """
            package sample.visibility

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.CreateAggregate

            @CreateAggregate data class Start(val value: String)
            data class Started(val value: String)
            internal enum class Measure { WATT }

            @AggregateRoot class Meter internal constructor(private val state: MeterState) {
                fun onCommand(command: Start): Started = Started(command.value)
            }
            internal class MeterState {
                var measure: Measure = Measure.WATT
                fun onSourcing(event: Started) = Unit
            }
            """.trimIndent(),
        )
        val (compilation, result) = compileModel("generate", domain)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        compilation.generated("MeterModel.kt").assert().contains("internal fun meterModel(")
        compilation.generated("MeterStateProperties.kt").assert().contains("internal object MeterStateProperties")
    }

    private fun com.tschuchort.compiletesting.KotlinCompilation.generated(name: String): String =
        workingDir.walkTopDown().single { it.name == name }.readText()

    /**
     * The generated object's own constants, in declaration order, as name to path; the snapshot's system fields are
     * those of the `SnapshotProperties` it extends.
     */
    private fun com.tschuchort.compiletesting.JvmCompilationResult.properties(name: String): Map<String, String> {
        val type = classLoader.loadClass(name)
        type.superclass.name.assert().isEqualTo(QueryPropertiesSource.SNAPSHOT_PROPERTIES)
        val instance = type.getField("INSTANCE").get(null)
        val queryField = classLoader.loadClass("me.ahoo.wow.api.query.QueryField")
        val path = queryField.getMethod("getPath")
        return type.declaredFields.filter { it.type == queryField }
            .associateTo(linkedMapOf()) { field ->
                field.isAccessible = true
                field.name to path.invoke(field.get(instance)) as String
            }
    }
}
