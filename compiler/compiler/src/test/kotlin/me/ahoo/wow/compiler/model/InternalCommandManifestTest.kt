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
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.RouteManifest
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * `@InternalCommand`: the Manifest lists the command as `internal`, with the routes any command of its kind has, so the
 * internal port serves them on the same paths; the other commands, the system ones among them, stay public.
 */
@OptIn(ExperimentalCompilerApi::class)
class InternalCommandManifestTest {
    private val sources = arrayOf(
        SourceFile.kotlin(
            "ShopContext.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.BoundedContext

            @BoundedContext(name = "shop")
            object ShopContext
            """.trimIndent(),
        ),
        SourceFile.kotlin(
            "Payment.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.CreateAggregate
            import me.ahoo.wow.api.annotation.InternalCommand
            import me.ahoo.wow.api.annotation.MountedCommand

            @CreateAggregate
            data class RequestPayment(val amount: Int)

            @InternalCommand
            data class ConfirmPayment(val transactionId: String)

            @InternalCommand
            @MountedCommand(aggregate = "payment", transformsTo = [ConfirmPayment::class])
            data class GatewayCallback(val transactionId: String)

            data class PaymentRequested(val amount: Int)
            data class PaymentConfirmed(val transactionId: String)

            @AggregateRoot(snapshot = true)
            class Payment(val id: String) {
                fun onCommand(command: RequestPayment): PaymentRequested = PaymentRequested(command.amount)
                fun onCommand(command: ConfirmPayment): PaymentConfirmed = PaymentConfirmed(command.transactionId)
                fun onSourcing(event: PaymentRequested) = Unit
                fun onSourcing(event: PaymentConfirmed) = Unit
            }
            """.trimIndent(),
        ),
    )

    @Test
    fun `an internal command should be listed as internal with its routes, and every other command as public`() {
        val (compilation, result) = compileModel("generate", *sources)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val fragment = compilation.workingDir.walkTopDown()
            .filter { it.isFile && it.parentFile.invariantSeparatorsPath.endsWith(Manifest.FRAGMENT_DIRECTORY) }
            .single()
        val tree = jacksonObjectMapper().readValue(fragment, Map::class.java)
        val commands = ManifestTree.read(
            tree,
            ManifestTree.REFUSE
        ).contexts.single().aggregates.single().commands.associateBy {
            it.name
        }

        val confirm = commands.getValue("ConfirmPayment")
        confirm.internal.assert().isTrue()
        confirm.routes.assert().containsExactly(
            RouteManifest(CommandRoutes.POST, "/shop/payment/{id}/confirm-payment"),
        )
        val callback = commands.getValue("GatewayCallback")
        callback.internal.assert().isTrue()
        callback.mounted.assert().isTrue()
        callback.routes.assert().hasSize(2)
        commands.getValue("RequestPayment").internal.assert().isFalse()
        commands.values.filter { it.system }.assert().isNotEmpty()
            .allSatisfy { it.internal.assert().isFalse() }

        // The fragment writes the flag on the internal commands alone.
        val written = ((tree["contexts"] as List<*>).single() as Map<*, *>)["aggregates"] as List<*>
        val flags = ((written.single() as Map<*, *>)["commands"] as List<*>).map { it as Map<*, *> }
            .associate { it["name"] to it["internal"] }
        flags.filterValues { it != null }.assert()
            .isEqualTo(mapOf("ConfirmPayment" to true, "GatewayCallback" to true))
    }
}
