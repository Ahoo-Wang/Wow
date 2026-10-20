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
            ),
        )
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
