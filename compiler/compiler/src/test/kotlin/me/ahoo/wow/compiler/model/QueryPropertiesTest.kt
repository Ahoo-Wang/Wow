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
import me.ahoo.wow.compiler.model.QueryPropertiesSource.toUpperSnake
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test

/**
 * The compiler generates `<State>Properties` for each aggregate's state when the module has `wow-query-api`. The
 * compiler does not depend on it, so these tests compile against stubs of its two types, as the real ones are.
 */
@OptIn(ExperimentalCompilerApi::class)
class QueryPropertiesTest {
    private val queryApi = SourceFile.kotlin(
        "QueryApi.kt",
        """
        package me.ahoo.wow.api.query

        data class QueryField(val path: String)

        open class SnapshotProperties protected constructor() {
            val TENANT_ID: QueryField = QueryField("tenantId")
        }
        """.trimIndent(),
    )

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
        val (_, result) = compileModel("generate", queryApi, order)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        result.properties("sample.OrderStateProperties").assert().isEqualTo(
            linkedMapOf(
                "STATE__ID" to "state.id",
                "STATE__CUSTOMER_ID" to "state.customerId",
                "STATE__STATUS" to "state.status",
                "STATE__ADDRESS" to "state.address",
                "STATE__ADDRESS__CITY" to "state.address.city",
                "STATE__ADDRESS__POSTAL_CODE" to "state.address.zip",
                "STATE__LINES" to "state.lines",
                "STATE__LINES__SKU" to "state.lines.sku",
                "STATE__LINES__QUANTITY" to "state.lines.quantity",
                "STATE__LINES__PARENT" to "state.lines.parent",
                "STATE__ATTRIBUTES" to "state.attributes",
                "STATE__TAGS" to "state.tags",
                "STATE__TOTAL_URL_COUNT" to "state.totalURLCount",
                "TENANT_ID" to "tenantId",
            ),
        )
    }

    @Test
    fun `a root that is its own state gets them too`() {
        val (_, result) = compileModel("generate", queryApi, order)
        result.properties("sample.LedgerProperties").assert().isEqualTo(
            linkedMapOf("STATE__ID" to "state.id", "STATE__BALANCE" to "state.balance", "TENANT_ID" to "tenantId"),
        )
    }

    @Test
    fun `without wow-query-api nothing is generated`() {
        val (compilation, result) = compileModel("generate", order)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        compilation.workingDir.walkTopDown().any { it.name.endsWith("Properties.kt") }.assert().isFalse()
    }

    @Test
    fun `names are upper snake case`() {
        "productId".toUpperSnake().assert().isEqualTo("PRODUCT_ID")
        "URLValue".toUpperSnake().assert().isEqualTo("URL_VALUE")
        "first-name".toUpperSnake().assert().isEqualTo("FIRST_NAME")
    }

    /** The generated object's constants, in declaration order (the inherited ones last), as name to path. */
    private fun com.tschuchort.compiletesting.JvmCompilationResult.properties(name: String): Map<String, String> {
        val type = classLoader.loadClass(name)
        val instance = type.getField("INSTANCE").get(null)
        val queryField = classLoader.loadClass("me.ahoo.wow.api.query.QueryField")
        val path = queryField.getMethod("getPath")
        return (type.declaredFields.toList() + type.superclass.declaredFields)
            .filter { it.type == queryField }
            .associateTo(linkedMapOf()) { field ->
                field.isAccessible = true
                field.name to path.invoke(field.get(instance)) as String
            }
    }
}
