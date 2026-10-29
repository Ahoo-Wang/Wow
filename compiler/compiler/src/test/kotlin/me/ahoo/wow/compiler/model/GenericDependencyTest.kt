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
import me.ahoo.wow.compiler.model.source.ModelProviderSource
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLClassLoader

/**
 * A dependency of a parameterized type is found by its full type, which the generated code builds without reflection
 * (`Types`), so `Catalog<Price>` and `Catalog<Map<String, Price>>` are two dependencies; a plain class is found
 * by its class, as before.
 */
@OptIn(ExperimentalCompilerApi::class)
class GenericDependencyTest {
    private val options = mapOf(
        ModelSymbolProcessorProvider.CONTEXT_OPTION to "shop",
        ModelSymbolProcessorProvider.MODULE_OPTION to "shop-domain",
    )

    private val sources = SourceFile.kotlin(
        "Shop.kt",
        """
        package sample.shop

        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate
        import me.ahoo.wow.api.annotation.OnEvent
        import me.ahoo.wow.api.annotation.Saga
        import me.ahoo.wow.api.subscribe.StateTypedEvent

        interface Catalog<V> { fun of(id: String): V }
        data class Price(val cents: Int)
        fun interface Clock { fun now(): Long }

        @CreateAggregate
        data class Quote(val product: String)
        data class Quoted(val cents: Int)

        @AggregateRoot
        class Cart(private val state: CartState) {
            fun onCommand(
                command: Quote,
                prices: Catalog<Price>,
                tiers: Catalog<Map<String, Price>>,
                clock: Clock,
            ): Quoted = Quoted(prices.of(command.product).cents * 10 + tiers.of(command.product).getValue("gold").cents)
        }

        class CartState(val id: String) {
            var cents = 0
            fun onSourcing(event: Quoted) { cents = event.cents }
        }

        @Saga
        class QuoteSaga(
            private val prices: Catalog<out Price>,
            private val anything: Catalog<*>,
            private val sinks: Catalog<in Price>,
        ) {
            @OnEvent
            fun onQuoted(event: StateTypedEvent<CartState, Quoted>) = Unit
        }
        """.trimIndent(),
    )

    /** Loads the module's provider as a host would, with dependencies that tell the catalogs apart by full type. */
    private val scenario = SourceFile.kotlin(
        "Scenario.kt",
        """
        @file:OptIn(me.ahoo.wow.api.annotation.InternalWowApi::class)

        package sample.shop

        import java.lang.reflect.Type
        import kotlinx.coroutines.runBlocking
        import me.ahoo.wow.api.decide.Command
        import me.ahoo.wow.kernel.AggregateModel
        import me.ahoo.wow.kernel.CommandOutcome
        import me.ahoo.wow.kernel.Dependencies
        import me.ahoo.wow.kernel.Kernel
        import me.ahoo.wow.kernel.ModelProvider
        import me.ahoo.wow.kernel.Types
        import me.ahoo.wow.kernel.memory.InMemoryEventLog

        val prices = object : Catalog<Price> { override fun of(id: String) = Price(4) }
        val tiers = object : Catalog<Map<String, Price>> { override fun of(id: String) = mapOf("gold" to Price(2)) }
        val clock = Clock { 0 }

        val byType = object : Dependencies {
            override fun find(type: Class<*>, qualifier: String?): Any? = if (type == Clock::class.java) clock else null

            override fun find(type: Type, qualifier: String?): Any? = when (type) {
                Types.parameterized(Catalog::class.java, Price::class.java) -> prices
                Types.parameterized(
                    Catalog::class.java,
                    Types.parameterized(Map::class.java, String::class.java, Price::class.java),
                ) -> tiers
                Types.parameterized(Catalog::class.java, Types.subtypeOf(Price::class.java)) -> prices
                Types.parameterized(Catalog::class.java, Types.unbounded()) -> tiers
                Types.parameterized(Catalog::class.java, Types.supertypeOf(Price::class.java)) -> prices
                else -> null
            }
        }

        fun run(loader: ClassLoader): Int = runBlocking {
            val provider = ModelProvider.load(loader).single()
            check(provider.processors(byType).size == 1)
            @Suppress("UNCHECKED_CAST")
            val cart = provider.models(byType).single() as AggregateModel<CartState>
            val outcome = Kernel(InMemoryEventLog()).execute(cart, Command("c1", "r1", cart.type.ref("t", "c-1"), Quote("mug")))
            ((outcome as CommandOutcome.Committed).state as CartState).cents
        }
        """.trimIndent(),
    )

    private fun generated(compilation: KotlinCompilation, name: String): File =
        compilation.workingDir.walkTopDown().first { it.isFile && it.name == name }

    @Test
    fun `a parameterized dependency should be found by its full type, a plain class by its class`() {
        val (compilation, result) = compileModel("generate", options, sources, scenario)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val types = "me.ahoo.wow.kernel.Types"
        generated(compilation, "ShopDomainModelProvider.kt").readText().assert().contains(
            "dependencies.get(sample.shop.Catalog::class.javaObjectType, " +
                "$types.parameterized(sample.shop.Catalog::class.java, sample.shop.Price::class.javaObjectType), " +
                "null, \"the Cart model\", \"catalog\") as sample.shop.Catalog<sample.shop.Price>",
            "dependencies.get(sample.shop.Catalog::class.javaObjectType, $types.parameterized(" +
                "sample.shop.Catalog::class.java, $types.parameterized(kotlin.collections.Map::class.java, " +
                "kotlin.String::class.javaObjectType, sample.shop.Price::class.javaObjectType)), null, " +
                "\"the Cart model\", \"catalog1\") as sample.shop.Catalog<kotlin.collections.Map<kotlin.String, " +
                "sample.shop.Price>>",
            // A plain class, as before.
            "dependencies.get(sample.shop.Clock::class.javaObjectType, null, \"the Cart model\")",
        )
        generated(compilation, "QuoteSagaSubscriptions.kt").readText().assert().contains(
            "$types.parameterized(sample.shop.Catalog::class.java, " +
                "$types.subtypeOf(sample.shop.Price::class.javaObjectType)), null, \"QuoteSaga\", \"prices\")",
            "$types.parameterized(sample.shop.Catalog::class.java, $types.unbounded()), null, \"QuoteSaga\", " +
                "\"anything\")",
            "$types.parameterized(sample.shop.Catalog::class.java, " +
                "$types.supertypeOf(sample.shop.Price::class.javaObjectType)), null, \"QuoteSaga\", \"sinks\")",
        )

        val resources = generated(compilation, ModelProviderSource.MODEL_PROVIDER).parentFile.parentFile.parentFile
        val loader = URLClassLoader(arrayOf(resources.toURI().toURL()), result.classLoader)
        val run = result.classLoader.loadClass("sample.shop.ScenarioKt").getMethod("run", ClassLoader::class.java)
        run.invoke(null, loader).assert().isEqualTo(42)
    }
}
