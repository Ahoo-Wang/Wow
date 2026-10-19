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
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.RetryManifest
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.URLClassLoader
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `@Retry` on a subscription processor and its handlers (roadmap P.3): the generated binding carries the budget, a
 * handler's settings over its processor's, and the Manifest's subscription entry records it.
 */
@OptIn(ExperimentalCompilerApi::class)
class RetryBindingTest {
    private val options = mapOf(
        ModelSymbolProcessorProvider.CONTEXT_OPTION to "shop",
        ModelSymbolProcessorProvider.MODULE_OPTION to "shop-domain",
    )

    private val retried = SourceFile.kotlin(
        "Retried.kt",
        """
        package sample.shop

        import me.ahoo.wow.api.annotation.OnEvent
        import me.ahoo.wow.api.annotation.Projection
        import me.ahoo.wow.api.annotation.Reactor
        import me.ahoo.wow.api.annotation.Retry
        import me.ahoo.wow.api.subscribe.StateEvent

        class OrderState(val id: String)

        class Carrier {
            class BusyException : RuntimeException()
        }

        @Reactor
        @Retry(maxAttempts = 3, timeout = "2s", unrecoverable = [IllegalArgumentException::class])
        object Notifier {
            @OnEvent
            @Retry(initialBackoff = "500ms", timeout = "PT5S", recoverable = [Carrier.BusyException::class])
            fun onEvent(event: StateEvent<OrderState>) = Unit

            @OnEvent
            fun inherited(event: StateEvent<OrderState>) = Unit
        }

        @Projection
        object Board {
            @OnEvent
            fun onEvent(event: StateEvent<OrderState>) = Unit

            @OnEvent
            @Retry(maxBackoff = "1m")
            fun own(event: StateEvent<OrderState>) = Unit
        }
        """.trimIndent(),
    )

    @Test
    fun `the binding and the Manifest should carry each handler's budget over its processor's`() {
        val (compilation, result) = compileModel("generate", options, retried)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val services = compilation.workingDir.walkTopDown()
            .first { it.isFile && it.name == ModelProviderSource.MODEL_PROVIDER }
        val loader = URLClassLoader(
            arrayOf(services.parentFile.parentFile.parentFile.toURI().toURL()),
            result.classLoader
        )
        val bindings = ModelProvider.load(loader).single().processors(Dependencies.NONE).associateBy { it.name }
        val busy = loader.loadClass("sample.shop.Carrier\$BusyException").asSubclass(Throwable::class.java)
        val invalid = listOf(IllegalArgumentException::class.java)
        bindings.getValue("Notifier.onEvent").retry.assert().isEqualTo(
            HandlerRetry(
                maxAttempts = 3,
                initialBackoff = 500.milliseconds,
                timeout = 5.seconds,
                recoverable = listOf(busy),
                unrecoverable = invalid,
            ),
        )
        bindings.getValue("Notifier.inherited").retry.assert()
            .isEqualTo(HandlerRetry(maxAttempts = 3, timeout = 2.seconds, unrecoverable = invalid))
        bindings.getValue("Board.onEvent").retry.assert().isNull()
        bindings.getValue("Board.own").retry.assert().isEqualTo(HandlerRetry(maxBackoff = 60.seconds))

        val fragment = compilation.workingDir.walkTopDown()
            .first { it.isFile && it.parentFile.invariantSeparatorsPath.endsWith(Manifest.FRAGMENT_DIRECTORY) }
        val subscriptions = ManifestTree.read(jacksonObjectMapper().readValue(fragment, Map::class.java))
            .contexts.single().subscriptions.associateBy { it.name }
        subscriptions.getValue("Notifier.onEvent").retry.assert().isEqualTo(
            RetryManifest(
                maxAttempts = 3,
                initialBackoff = "PT0.500S",
                timeout = "PT5S",
                recoverable = listOf("sample.shop.Carrier.BusyException"),
                unrecoverable = listOf("java.lang.IllegalArgumentException"),
            ),
        )
        subscriptions.getValue("Notifier.inherited").retry.assert().isEqualTo(
            RetryManifest(3, timeout = "PT2S", unrecoverable = listOf("java.lang.IllegalArgumentException")),
        )
        subscriptions.getValue("Board.onEvent").retry.assert().isNull()
        subscriptions.getValue("Board.own").retry.assert().isEqualTo(RetryManifest(maxBackoff = "PT1M"))
    }

    @Test
    fun `a budget the runtime cannot take, or @Retry where no subscription is, should fail the compilation`() {
        val broken = SourceFile.kotlin(
            "Broken.kt",
            """
            package sample.shop

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.OnRejected
            import me.ahoo.wow.api.annotation.Reactor
            import me.ahoo.wow.api.annotation.Retry
            import me.ahoo.wow.api.annotation.Saga
            import me.ahoo.wow.api.decide.Command
            import me.ahoo.wow.api.subscribe.CommandRejected
            import me.ahoo.wow.api.subscribe.StateEvent

            data class Open(val id: String)
            data class Opened(val id: String)

            @AggregateRoot
            @Retry(maxAttempts = 2)
            class Order(val id: String) {
                fun onCommand(command: Open): Opened = Opened(command.id)
                fun onSourcing(event: Opened) = Unit
            }

            @Reactor
            @Retry(maxAttempts = -1, initialBackoff = "soon")
            class Shaky {
                @OnEvent
                @Retry(maxBackoff = "-1s", timeout = "0s")
                fun onEvent(event: StateEvent<Order>) = Unit
            }

            @Saga
            class Compensating {
                @OnEvent
                fun onEvent(event: StateEvent<Order>): Command<Open>? = null

                @OnRejected
                @Retry(maxAttempts = 2)
                fun onRejected(rejected: CommandRejected<Open>): Command<Open>? = null
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, broken)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .contains("aggregate root Order is annotated @Retry, which only a subscription processor")
            .contains("processor Shaky is annotated @Retry(maxAttempts = -1): a call is attempted at least once")
            .contains("processor Shaky is annotated @Retry(initialBackoff = \"soon\"): it is a duration")
            .contains("handler Shaky.onEvent is annotated @Retry(maxBackoff = \"-1s\"): it is never negative")
            .contains("handler Shaky.onEvent is annotated @Retry(timeout = \"0s\"): it is positive")
            .contains("handler Compensating.onRejected is annotated @Retry: an @OnRejected handler compensates")
    }

    @Test
    fun `a 9x setting of @Retry should fail the compilation`() {
        // 9.x's @Retry, with settings V10's does not have, in place of V10's.
        val nineX = SourceFile.kotlin(
            "NineXRetry.kt",
            """
            package me.ahoo.wow.api.annotation

            annotation class Retry(
                val enabled: Boolean = true,
                val maxRetries: Int = 3,
                val maxAttempts: Int = 0,
                val initialBackoff: String = "",
                val maxBackoff: String = "",
                val timeout: String = "",
            )
            """.trimIndent(),
        )
        val legacy = SourceFile.kotlin(
            "Legacy.kt",
            """
            package sample.shop

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Reactor
            import me.ahoo.wow.api.annotation.Retry
            import me.ahoo.wow.api.subscribe.StateEvent

            class OrderState(val id: String)

            @Reactor
            class Legacy {
                @OnEvent
                @Retry(maxRetries = 5)
                fun onEvent(event: StateEvent<OrderState>) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, nineX, legacy)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .contains("handler Legacy.onEvent is annotated @Retry(enabled), a 9.x setting V10 does not have")
            .contains("handler Legacy.onEvent is annotated @Retry(maxRetries), a 9.x setting V10 does not have")
    }
}
