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
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.manifest.SnapshotManifest
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * `@AggregateRoot(snapshot = false)` (decisions D4, D195): the fragment marks the aggregate's snapshots disabled and
 * lists no snapshot query for it; the others keep theirs.
 */
@OptIn(ExperimentalCompilerApi::class)
class SnapshotManifestTest {
    @Test
    fun `@AggregateRoot(snapshot = false) should turn the aggregate's snapshot off in the fragment`() {
        val sources = SourceFile.kotlin(
            "Ledger.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.BoundedContext

            @BoundedContext(name = "bank")
            object BankContext

            data class Open(val id: String)
            data class Opened(val id: String)

            @AggregateRoot(snapshot = false)
            class Ledger(val id: String) {
                fun onCommand(command: Open): Opened = Opened(command.id)
                fun onSourcing(event: Opened) = Unit
            }

            @AggregateRoot
            class Account(val id: String) {
                fun onCommand(command: Open): Opened = Opened(command.id)
                fun onSourcing(event: Opened) = Unit
            }
            """.trimIndent(),
        )
        val (compilation, result) = compileModel("generate", sources)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val fragment = compilation.workingDir.walkTopDown()
            .single { it.isFile && it.parentFile.invariantSeparatorsPath.endsWith(Manifest.FRAGMENT_DIRECTORY) }
        val aggregates = ManifestTree.read(jacksonObjectMapper().readValue(fragment, Map::class.java))
            .contexts.single().aggregates.associateBy { it.name }
        val ledger = aggregates.getValue("ledger")
        ledger.snapshot.assert().isEqualTo(SnapshotManifest(enabled = false))
        ledger.queries.map { it.model }.assert().containsExactly(QueryRoutes.EVENTS)
        val account = aggregates.getValue("account")
        account.snapshot.assert().isEqualTo(SnapshotManifest(enabled = true))
        account.queries.map { it.model }.assert().containsExactly(QueryRoutes.SNAPSHOT, QueryRoutes.EVENTS)
    }

    @Test
    fun `@AggregateRoot(stateRevision) should reach the model and the fragment`() {
        val sources = SourceFile.kotlin(
            "Ledger.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.BoundedContext

            @BoundedContext(name = "bank")
            object BankContext

            data class Open(val id: String)
            data class Opened(val id: String)

            @AggregateRoot(stateRevision = 3)
            class Ledger(val id: String) {
                fun onCommand(command: Open): Opened = Opened(command.id)
                fun onSourcing(event: Opened) = Unit
            }

            @AggregateRoot
            class Account(val id: String) {
                fun onCommand(command: Open): Opened = Opened(command.id)
                fun onSourcing(event: Opened) = Unit
            }
            """.trimIndent(),
        )
        val (compilation, result) = compileModel("generate", sources)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val fragment = compilation.workingDir.walkTopDown()
            .single { it.isFile && it.parentFile.invariantSeparatorsPath.endsWith(Manifest.FRAGMENT_DIRECTORY) }
        val aggregates = ManifestTree.read(jacksonObjectMapper().readValue(fragment, Map::class.java))
            .contexts.single().aggregates.associateBy { it.name }
        aggregates.getValue("ledger").snapshot.assert().isEqualTo(SnapshotManifest(stateRevision = 3))
        aggregates.getValue("account").snapshot.assert().isEqualTo(SnapshotManifest())
        compilation.workingDir.walkTopDown().single { it.name == "LedgerModel.kt" }.readText()
            .assert().contains("stateRevision(3)")
        compilation.workingDir.walkTopDown().single { it.name == "AccountModel.kt" }.readText()
            .assert().doesNotContain("stateRevision(")
    }

    @Test
    fun `a state revision below 1 should be a problem`() {
        val sources = SourceFile.kotlin(
            "Ledger.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.BoundedContext

            @BoundedContext(name = "bank")
            object BankContext

            data class Open(val id: String)
            data class Opened(val id: String)

            @AggregateRoot(stateRevision = 0)
            class Ledger(val id: String) {
                fun onCommand(command: Open): Opened = Opened(command.id)
                fun onSourcing(event: Opened) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", sources)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains("@AggregateRoot(stateRevision) is 0")
    }
}
