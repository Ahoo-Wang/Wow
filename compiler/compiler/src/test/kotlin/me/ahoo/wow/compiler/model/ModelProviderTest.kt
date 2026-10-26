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

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.lifecycle.ApplyTags
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.MissingDependencyException
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.net.URLClassLoader

/** Decisions D107 and D91: the module's binding index, and `@AggregateRoot(lifecycle)`. */
@OptIn(ExperimentalCompilerApi::class)
class ModelProviderTest {
    private val options = mapOf(
        ModelSymbolProcessorProvider.CONTEXT_OPTION to "bank",
        ModelSymbolProcessorProvider.MODULE_OPTION to "bank-domain",
    )

    private val account = SourceFile.kotlin(
        "Account.kt",
        """
        package sample.account

        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate

        @CreateAggregate
        data class Open(val amount: Int)
        data class Opened(val amount: Int)
        fun interface Fees { fun of(amount: Int): Int }
        fun interface Limits<T> { fun of(key: T): Int }

        @AggregateRoot
        class Account(private val state: AccountState) {
            fun onCommand(command: Open, fees: Fees, limits: Limits<String>): Opened =
                Opened(minOf(command.amount, limits.of("open")) - fees.of(command.amount))
        }

        class AccountState(val id: String) {
            var balance = 0
            fun onSourcing(event: Opened) { balance += event.amount }
        }
        """.trimIndent(),
    )

    /** Loads the module's provider as a host would, gives it the domain services and runs a command. */
    private val scenario = SourceFile.kotlin(
        "Scenario.kt",
        """
        @file:OptIn(me.ahoo.wow.api.annotation.InternalWowApi::class)

        package sample.account

        import kotlinx.coroutines.runBlocking
        import me.ahoo.wow.api.decide.Command
        import me.ahoo.wow.kernel.AggregateModel
        import me.ahoo.wow.kernel.CommandOutcome
        import me.ahoo.wow.kernel.Dependencies
        import me.ahoo.wow.kernel.memory.InMemoryEventLog
        import me.ahoo.wow.kernel.Kernel
        import me.ahoo.wow.kernel.ModelProvider

        fun run(loader: ClassLoader): String = runBlocking {
            val provider = ModelProvider.load(loader).single()
            val models = provider.models(Dependencies.of(Fees { 1 }, Limits<String> { 10 }))
            @Suppress("UNCHECKED_CAST")
            val account = models.single { it.type.name == "account" } as AggregateModel<AccountState>
            val ref = account.type.ref("tenant", "a-1")
            val outcome = Kernel(InMemoryEventLog()).execute(account, Command("c1", "r1", ref, Open(20)))
            models.joinToString { it.type.toString() } + ": " +
                ((outcome as CommandOutcome.Committed).state as AccountState).balance
        }
        """.trimIndent(),
    )

    private val ledger = SourceFile.kotlin(
        "Ledger.kt",
        """
        package sample.ledger

        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.lifecycle.ApplyTags
        import me.ahoo.wow.api.lifecycle.DeleteAggregate

        data class Post(val note: String)
        data class Posted(val note: String)

        @AggregateRoot(lifecycle = [DeleteAggregate::class, ApplyTags::class, DeleteAggregate::class])
        class Ledger(val id: String) {
            fun onCommand(command: Post): Posted = Posted(command.note)
            fun onSourcing(event: Posted) = Unit
        }
        """.trimIndent(),
    )

    private fun compile(vararg sources: SourceFile): Pair<KotlinCompilation, JvmCompilationResult> {
        val (compilation, result) = compileModel("generate", options, *sources)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        return compilation to result
    }

    private fun generated(compilation: KotlinCompilation, name: String): File? =
        compilation.workingDir.walkTopDown().firstOrNull { it.isFile && it.name == name }

    /**
     * The compiled classes with the resources KSP generated (its services file and fragment), which the compilation's
     * own class loader leaves out, as a jar would hold them.
     */
    private fun classLoader(compilation: KotlinCompilation, result: JvmCompilationResult): ClassLoader {
        val resources = generated(compilation, ModelProviderSource.MODEL_PROVIDER)?.parentFile?.parentFile?.parentFile
            ?: return result.classLoader
        return URLClassLoader(arrayOf(resources.toURI().toURL()), result.classLoader)
    }

    private fun provider(compilation: KotlinCompilation, result: JvmCompilationResult): ModelProvider =
        ModelProvider.load(classLoader(compilation, result)).single()

    private fun fragment(compilation: KotlinCompilation): Manifest = ManifestTree.read(
        jacksonObjectMapper().readValue(generated(compilation, "bank-domain.json"), Map::class.java),
    )

    @Test
    fun `the module's provider should build every model with the dependencies of its factory`() {
        val (compilation, result) = compile(account, ledger, scenario)
        val services = generated(compilation, ModelProviderSource.MODEL_PROVIDER)!!
        services.parentFile.invariantSeparatorsPath.assert().endsWith("META-INF/services")
        services.readText().assert().isEqualTo("sample.BankDomainModelProvider\n")
        generated(compilation, "BankDomainModelProvider.kt")!!.readText().assert().contains(
            "sample.account.accountModel(context, dependencies.get(sample.account.Fees::class.javaObjectType), " +
                "dependencies.get(sample.account.Limits::class.javaObjectType) as sample.account.Limits<kotlin.String>)",
            "sample.ledger.ledgerModel(context)",
        )

        val provider = provider(compilation, result)
        provider.javaClass.name.assert().isEqualTo("sample.BankDomainModelProvider")
        provider.context.assert().isEqualTo("bank")
        // Generated for the binding of the kernel this compiler is released with (finding R5.A-10).
        provider.binding.assert().isEqualTo(ModelProvider.BINDING)
        ModelProviderSource.BINDING.assert().isEqualTo(ModelProvider.BINDING)
        provider.fragment.assert().isEqualTo("${Manifest.FRAGMENT_DIRECTORY}/bank-domain.json")
        val loader = classLoader(compilation, result)
        loader.getResource(provider.fragment).assert().isNotNull()

        val run = result.classLoader.loadClass("sample.account.ScenarioKt").getMethod("run", ClassLoader::class.java)
        run.invoke(null, loader).assert().isEqualTo("bank.account, bank.ledger: 9")
    }

    @Test
    fun `a missing dependency should fail the models' assembly, naming its type`() {
        val (compilation, result) = compile(account)
        assertThrownBy<MissingDependencyException> { provider(compilation, result).models(Dependencies.NONE) }
            .hasMessageContaining("No dependency of sample.account.Fees to build the aggregate models with")
    }

    @Test
    fun `a module without a bounded context should have no provider`() {
        val (compilation, result) = compileModel("generate", account)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        generated(compilation, ModelProviderSource.MODEL_PROVIDER).assert().isNull()
        ModelProvider.load(result.classLoader).assert().isEmpty()
    }

    @Test
    fun `a module of subscriptions only should have a provider of no model`() {
        val summary = SourceFile.kotlin(
            "Summary.kt",
            """
            package sample.view

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.subscribe.StateEvent

            @Projection
            class Summary {
                @OnEvent
                fun onEvent(event: StateEvent<*>) = Unit
            }
            """.trimIndent(),
        )
        val (compilation, result) = compile(summary)
        val provider = provider(compilation, result)
        provider.javaClass.name.assert().isEqualTo("sample.view.BankDomainModelProvider")
        provider.models(Dependencies.NONE).assert().isEmpty()
    }

    @Test
    fun `@AggregateRoot(lifecycle) should narrow the model's lifecycle commands and the fragment's`() {
        val (compilation, result) = compile(ledger)
        provider(
            compilation,
            result
        ).models(Dependencies.NONE).single().lifecycleCommands.assert().containsExactlyInAnyOrder(
            DeleteAggregate::class.java,
            ApplyTags::class.java,
        )
        generated(compilation, "LedgerModel.kt")!!.readText().assert().contains(
            "lifecycle(me.ahoo.wow.api.lifecycle.ApplyTags::class.java, " +
                "me.ahoo.wow.api.lifecycle.DeleteAggregate::class.java)",
        )
        val (allCompilation, _) = compile(account, ledger)
        generated(allCompilation, "AccountModel.kt")!!.readText().assert().doesNotContain("lifecycle(")
        val aggregates = fragment(allCompilation).contexts.single().aggregates.associate { it.name to it.lifecycle }
        aggregates.assert().isEqualTo(
            mapOf(
                "account" to Lifecycle.COMMANDS.keys.sorted(),
                "ledger" to listOf(Lifecycle.APPLY_TAGS, Lifecycle.DELETE_AGGREGATE),
            ),
        )
    }

    @Test
    fun `an empty @AggregateRoot(lifecycle) should enable no lifecycle command`() {
        val closed = SourceFile.kotlin(
            "Closed.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot

            data class Post(val note: String)
            data class Posted(val note: String)

            @AggregateRoot(lifecycle = [])
            class Closed(val id: String) {
                fun onCommand(command: Post): Posted = Posted(command.note)
                fun onSourcing(event: Posted) = Unit
            }
            """.trimIndent(),
        )
        val (compilation, result) = compile(closed)
        provider(compilation, result).models(Dependencies.NONE).single().lifecycleCommands.assert().isEmpty()
        fragment(compilation).contexts.single().aggregates.single().lifecycle.assert().isEmpty()
    }

    @Test
    fun `@AggregateRoot(lifecycle) should list lifecycle commands only`() {
        val wrong = SourceFile.kotlin(
            "Wrong.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.lifecycle.AggregateDeleted

            data class Post(val note: String)
            data class Posted(val note: String)

            @AggregateRoot(lifecycle = [AggregateDeleted::class, Post::class])
            class Wrong(val id: String) {
                fun onCommand(command: Post): Posted = Posted(command.note)
                fun onSourcing(event: Posted) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, wrong)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .contains(
                "@AggregateRoot(lifecycle) lists me.ahoo.wow.api.lifecycle.AggregateDeleted, which is not a " +
                    "lifecycle command: one of ApplyTags, DeleteAggregate, RestoreAggregate, TransferOwner, " +
                    "TransferSpace",
            )
            .contains("@AggregateRoot(lifecycle) lists sample.Post, which is not a lifecycle command")
    }

    @Test
    fun `a root in the root package should get a provider there`() {
        val note = SourceFile.kotlin(
            "Note.kt",
            """
            import me.ahoo.wow.api.annotation.AggregateRoot

            data class Write(val text: String)
            data class Written(val text: String)

            @AggregateRoot
            class Note(val id: String) {
                fun onCommand(command: Write): Written = Written(command.text)
                fun onSourcing(event: Written) = Unit
            }
            """.trimIndent(),
        )
        val (compilation, result) = compileModel(
            "generate",
            mapOf(
                ModelSymbolProcessorProvider.CONTEXT_OPTION to "notes",
                ModelSymbolProcessorProvider.MODULE_OPTION to "9-notes",
            ),
            note,
        )
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        generated(compilation, ModelProviderSource.MODEL_PROVIDER)!!.readText().assert()
            .isEqualTo("Module9NotesModelProvider\n")
        provider(compilation, result).models(Dependencies.NONE).single().type.toString().assert()
            .isEqualTo("notes.note")
    }

    @Test
    fun `the provider's name should follow the module, and its package the module's common one`() {
        ModelProviderSource.className("shop-domain").assert().isEqualTo("ShopDomainModelProvider")
        ModelProviderSource.className("_9.x").assert().isEqualTo("Module9XModelProvider")
        ModelProviderSource.className("--").assert().isEqualTo("ModuleModelProvider")
        ModelProviderSource.commonPackage(listOf("a.b.c", "a.b.d", "a.b")).assert().isEqualTo("a.b")
        ModelProviderSource.commonPackage(listOf("a.b", "c")).assert().isEqualTo("")
        ModelProviderSource.commonPackage(listOf("a.b", "")).assert().isEqualTo("")
        ModelProviderSource.commonPackage(emptyList()).assert().isEqualTo("")
    }
}
