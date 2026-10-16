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
 * The parameters of an after hook (finding A4-3): read as a command function's, and a hook of a command type runs
 * only for the commands it takes, so the generated cast never fails, after a creation or a lifecycle command either.
 */
@OptIn(ExperimentalCompilerApi::class)
class AfterHookParametersTest {
    private fun cart(hooks: String): SourceFile = SourceFile.kotlin(
        "Cart.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AfterCommand
        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate
        import me.ahoo.wow.api.decide.Command
        import me.ahoo.wow.api.decide.DecideScope

        @CreateAggregate
        data class Create(val id: String)
        data class Created(val id: String)
        sealed interface ItemCommand { val item: String }
        data class Add(override val item: String) : ItemCommand
        data class Remove(override val item: String) : ItemCommand
        data class Added(val item: String)
        data class Removed(val item: String)
        data class Audited(val by: String)

        @AggregateRoot
        class Cart(private val state: CartState) {
            fun onCommand(command: Create): Created = Created(command.id)
            fun onCommand(command: Add): Added = Added(command.item)
            fun onCommand(command: Remove): Removed = Removed(command.item)
            $hooks
        }

        class CartState {
            fun onSourcing(event: Created) {}
            fun onSourcing(event: Added) {}
            fun onSourcing(event: Removed) {}
            fun onSourcing(event: Audited) {}
        }
        """.trimIndent(),
    )

    private val scenario = SourceFile.kotlin(
        "Scenario.kt",
        """
        @file:OptIn(me.ahoo.wow.api.annotation.InternalWowApi::class)

        package sample

        import kotlinx.coroutines.runBlocking
        import me.ahoo.wow.api.decide.Command
        import me.ahoo.wow.api.lifecycle.DeleteAggregate
        import me.ahoo.wow.kernel.CommandOutcome
        import me.ahoo.wow.kernel.Kernel
        import me.ahoo.wow.kernel.memory.InMemoryEventLog

        fun run(): String = runBlocking {
            val model = cartModel("shop")
            val kernel = Kernel(InMemoryEventLog())
            val ref = model.type.ref("tenant", "cart-1")
            listOf(Create("cart-1"), Add("apple"), Remove("apple"), DeleteAggregate).mapIndexed { index, body ->
                when (val outcome = kernel.execute(model, Command("c" + index, "r" + index, ref, body))) {
                    is CommandOutcome.Committed -> outcome.stream.events.joinToString("+") {
                        it.name.substringAfterLast('.') + (if (it.name.endsWith("Audited")) it.payload else "")
                    }
                    else -> outcome.toString()
                }
            }.joinToString(" | ")
        }
        """.trimIndent(),
    )

    private fun run(hooks: String): String {
        val (_, result) = compileModel("generate", cart(hooks), scenario)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        return result.classLoader.loadClass("sample.ScenarioKt").getMethod("run").invoke(null) as String
    }

    @Test
    fun `a hook taking a command type should run only for that command`() {
        run("fun afterCommand(command: Add): Audited = Audited(\"add:\" + command.item)").assert().isEqualTo(
            "Created | Added+Audited{\"by\":\"add:apple\"} | Removed | AggregateDeleted",
        )
    }

    @Test
    fun `a hook taking a sealed command type should run for each command of it`() {
        run("@AfterCommand fun audit(command: ItemCommand): Audited = Audited(command.item)").assert().isEqualTo(
            "Created | Added+Audited{\"by\":\"apple\"} | Removed+Audited{\"by\":\"apple\"} | AggregateDeleted",
        )
    }

    @Test
    fun `a hook taking the admitted command should get it`() {
        run(
            "@AfterCommand(include = [Add::class]) " +
                "fun audit(command: Command<Add>): Audited = Audited(command.requestId + \":\" + command.body.item)",
        ).assert().isEqualTo("Created | Added+Audited{\"by\":\"r1:apple\"} | Removed | AggregateDeleted")
    }

    @Test
    fun `a hook taking only the decide scope should get it`() {
        run(
            "@AfterCommand(include = [Remove::class]) " +
                "fun audit(scope: DecideScope): Audited = Audited(scope.command.requestId)",
        ).assert().isEqualTo("Created | Added | Removed+Audited{\"by\":\"r2\"} | AggregateDeleted")
    }

    @Test
    fun `a hook including a command it does not take should be a compile error`() {
        val (_, result) = compileModel(
            "generate",
            cart("@AfterCommand(include = [Create::class]) fun audit(command: Add): Audited = Audited(\"x\")"),
        )
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains(
            "after hook audit includes sample.Create, but takes sample.Add: each command it includes is one it takes",
        )
    }

    @Test
    fun `a hook taking a type no command function takes should be a compile error`() {
        val (_, result) = compileModel("generate", cart("fun afterCommand(command: Audited): Audited = command"))
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains(
            "after hook afterCommand takes sample.Audited, which no command function of the aggregate takes",
        )
    }
}
