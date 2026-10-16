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
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

/** `@AggregateRoot(retiredEvents)` (decision D219) as the model processor reads, checks and generates it. */
@OptIn(ExperimentalCompilerApi::class)
class RetiredEventsTest {
    /** A ledger retiring [retired] (the annotation's argument, as written). */
    private fun ledger(retired: String): SourceFile = SourceFile.kotlin(
        "Ledger.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.Alias
        import me.ahoo.wow.api.annotation.MountedCommand
        import me.ahoo.wow.api.annotation.Name

        @Alias("Open")
        data class Enter(val amount: Long)

        @Name("Booked")
        @Alias("Entered")
        data class Entered(val amount: Long)

        @MountedCommand(aggregate = "ledger")
        data class Import(val amount: Long)

        @AggregateRoot(retiredEvents = [$retired])
        class Ledger(private val state: LedgerState) {
            fun onCommand(command: Enter): Entered = Entered(command.amount)
        }

        class LedgerState(val id: String) {
            var total = 0L
            fun onSourcing(event: Entered) { total += event.amount }
        }
        """.trimIndent(),
    )

    private val context = mapOf(ModelSymbolProcessorProvider.CONTEXT_OPTION to "bank")

    @Test
    fun `retired events are passed to the generated model`() {
        val scenario = SourceFile.kotlin(
            "LedgerScenario.kt",
            """
            @file:OptIn(me.ahoo.wow.api.annotation.InternalWowApi::class)

            package sample

            import me.ahoo.wow.kernel.EventCodec
            import me.ahoo.wow.spi.log.EventRecord

            fun retired(): Set<String> = ledgerModel("bank").retiredEvents

            fun read(): List<Any> = EventCodec().decodeEvents(
                ledgerModel("bank"),
                listOf(
                    EventRecord("bank.ledger.Withdrawn", 1, "{\"amount\":5}"),
                    EventRecord("bank.ledger.Booked", 1, "{\"amount\":7}"),
                    EventRecord("legacy.ledger.Closed", 1, "{}"),
                ),
            )
            """.trimIndent(),
        )
        val (compilation, result) =
            compileModel("generate", context, ledger("\"Withdrawn\", \"legacy.ledger.Closed\""), scenario)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val generated = compilation.workingDir.walkTopDown().single { it.name == "LedgerModel.kt" }.readText()
        generated.assert().contains("retired(\"\$type.Withdrawn\", \"legacy.ledger.Closed\")")
        val scenarioClass = result.classLoader.loadClass("sample.LedgerScenarioKt")
        scenarioClass.getMethod("retired").invoke(null)
            .assert().isEqualTo(setOf("bank.ledger.Withdrawn", "legacy.ledger.Closed"))
        scenarioClass.getMethod("read").invoke(null).toString().assert().isEqualTo("[Entered(amount=7)]")
        // The Manifest records them (finding A4-12), as written.
        val fragment = compilation.workingDir.walkTopDown()
            .single { it.isFile && it.parentFile.invariantSeparatorsPath.endsWith(Manifest.FRAGMENT_DIRECTORY) }
        val manifest = ManifestTree.read(jacksonObjectMapper().readValue(fragment, Map::class.java))
        manifest.contexts.single().aggregates.single().retired
            .assert().containsExactly("Withdrawn", "legacy.ledger.Closed")
        ManifestTree.read(ManifestTree.write(manifest)).assert().isEqualTo(manifest)
    }

    @Test
    fun `no retired events generate no call`() {
        val (compilation, result) = compileModel("generate", context, ledger(""))
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        compilation.workingDir.walkTopDown().single { it.name == "LedgerModel.kt" }.readText()
            .assert().doesNotContain("retired(")
    }

    @Test
    fun `a retired name the aggregate still uses is a compile error`() {
        val retired = listOf(
            "\"Booked\"",
            "\"bank.ledger.Entered\"",
            "\"Enter\"",
            "\"Open\"",
            "\"Import\"",
            "\"wow.lifecycle.AggregateDeleted\"",
            "\"ledger.Gone\"",
            "\" \"",
            "\"Gone\"",
            "\"Gone\"",
        ).joinToString()
        val (_, result) = compileModel("generate", context, ledger(retired))
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        val where = "@AggregateRoot(retiredEvents)"
        result.messages.assert()
            .contains("$where(\"Booked\") is the name or a former name of event Entered")
            .contains("$where(\"bank.ledger.Entered\") is the name or a former name of event Entered")
            .contains("$where(\"Enter\") is the name or a former name of command Enter")
            .contains("$where(\"Open\") is the name or a former name of command Enter")
            .contains("$where(\"Import\") is the name or a former name of mounted command Import")
            .contains("$where(\"wow.lifecycle.AggregateDeleted\") is a system name")
            .contains("$where(\"ledger.Gone\") has exactly one dot")
            .contains("$where has a blank value")
            .contains("$where lists \"Gone\" twice")
    }
}
