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
 * A command with a `String` property named `id` and no `@AggregateId` is warned of where it could name its aggregate:
 * Wow 10 takes a command's aggregate from its body only by a marked property.
 */
@OptIn(ExperimentalCompilerApi::class)
class UnmarkedAggregateIdTest {
    private val context = SourceFile.kotlin(
        "ShopContext.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.BoundedContext

        @BoundedContext(name = "shop")
        object ShopContext
        """.trimIndent(),
    )

    /** A stock with [commands] and the root annotated [root], whose command functions are [functions]. */
    private fun stock(commands: String, functions: String, root: String = "@AggregateRoot"): SourceFile =
        SourceFile.kotlin(
            "Stock.kt",
            """
            package sample

            import me.ahoo.wow.api.aggregate.AggregateIdSource
            import me.ahoo.wow.api.annotation.AggregateId
            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.CreateAggregate
            import me.ahoo.wow.api.annotation.MountedCommand
            import me.ahoo.wow.api.annotation.Unaddressed

            $commands
            data class Stocked(val quantity: Int)

            $root
            class Stock(val id: String) {
                $functions
                fun onSourcing(event: Stocked) = Unit
            }
            """.trimIndent(),
        )

    /** The compiler's messages for [sources], which compile. */
    private fun compiled(vararg sources: SourceFile): String {
        val (_, result) = compileModel("generate", context, *sources)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        return result.messages
    }

    @Test
    fun `a command's id it does not mark should be warned of, stored or computed from an interface`() {
        compiled(
            stock(
                commands = """
                data class ReserveStock(val id: String, val quantity: Int)
                interface Identified {
                    val id: String
                }
                data class Restock(val warehouse: String, val sku: String) : Identified {
                    override val id: String get() = "${'$'}warehouse-${'$'}sku"
                }
                @CreateAggregate
                data class OpenStock(val id: String?, val quantity: Int)
                """.trimIndent(),
                functions = """
                fun onCommand(command: ReserveStock): Stocked = Stocked(command.quantity)
                fun onCommand(command: Restock): Stocked = Stocked(0)
                fun onCommand(command: OpenStock): Stocked = Stocked(command.quantity)
                """.trimIndent(),
            ),
        ).assert()
            .contains(
                "sample.Stock: command ReserveStock has a String property id and no @AggregateId property: Wow 10 " +
                    "does not take a command's aggregate from its body unless a property is marked @AggregateId. If " +
                    "id names ReserveStock's aggregate, mark it @AggregateId, as a 9.x command whose body named its " +
                    "aggregate needs, a creation a saga sends without an aggregate id among them. If 9.x bound it " +
                    "from the path, it only repeated the route: remove it and read command.ref.id. Otherwise " +
                    "suppress this with @Suppress(\"UnmarkedAggregateId\").",
            )
            .contains("command Restock has a String property id and no @AggregateId property")
            .contains("command OpenStock has a String property id and no @AggregateId property")
    }

    @Test
    fun `a command that marks a property, or holds no String id, should not be warned of`() {
        compiled(
            stock(
                commands = """
                data class Count(@AggregateId val id: String)
                data class Move(@AggregateId val productId: String, val id: String)
                interface OfProduct {
                    @AggregateId
                    val id: String
                }
                data class Adjust(override val id: String, val quantity: Int) : OfProduct
                data class Tally(val id: Long)
                data class Close(val reason: String)
                """.trimIndent(),
                functions = """
                fun onCommand(command: Count): Stocked = Stocked(0)
                fun onCommand(command: Move): Stocked = Stocked(0)
                fun onCommand(command: Adjust): Stocked = Stocked(command.quantity)
                fun onCommand(command: Tally): Stocked = Stocked(0)
                fun onCommand(command: Close): Stocked = Stocked(0)
                """.trimIndent(),
            ),
        ).assert().doesNotContain("and no @AggregateId property")
    }

    @Test
    fun `a command that suppresses the warning should not be warned of, and one suppressing something else should`() {
        compiled(
            stock(
                commands = """
                @Suppress("SomethingElse", "UnmarkedAggregateId")
                data class Note(val id: String)
                @Suppress("SomethingElse")
                data class Mark(val id: String)
                """.trimIndent(),
                functions = """
                fun onCommand(command: Note): Stocked = Stocked(0)
                fun onCommand(command: Mark): Stocked = Stocked(0)
                """.trimIndent(),
            ),
        ).assert()
            .doesNotContain("command Note has")
            .contains("command Mark has a String property id and no @AggregateId property")
    }

    @Test
    fun `an unaddressed or mounted command should not be warned of`() {
        compiled(
            stock(
                commands = """
                @Unaddressed
                data class ImportStock(val id: String, val quantity: Int)
                @MountedCommand(aggregate = "stock")
                data class SyncStock(val id: String)
                """.trimIndent(),
                functions = "fun onCommand(command: ImportStock): Stocked = Stocked(command.quantity)",
            ),
        ).assert().doesNotContain("and no @AggregateId property")
    }

    @Test
    fun `a command of an aggregate whose id is its owner's should not be warned of`() {
        compiled(
            stock(
                commands = "data class ReserveStock(val id: String, val quantity: Int)",
                functions = "fun onCommand(command: ReserveStock): Stocked = Stocked(command.quantity)",
                root = "@AggregateRoot(idSource = AggregateIdSource.OWNER)",
            ),
        ).assert().doesNotContain("and no @AggregateId property")
    }

    @Test
    fun `a command of a library should not be warned of`() {
        // Its author marks it, and its @Suppress does not reach this compilation.
        val library = compileLibrary(
            SourceFile.kotlin(
                "Restock.kt",
                """
                package base

                data class Restock(val id: String, val quantity: Int)
                """.trimIndent(),
            ),
        )
        val (_, result) = compileModelAgainst(
            "generate",
            listOf(library),
            context,
            stock(
                commands = "",
                functions = "fun onCommand(command: base.Restock): Stocked = Stocked(command.quantity)",
            ),
        )
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        result.messages.assert().doesNotContain("and no @AggregateId property")
    }
}
