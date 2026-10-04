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

package me.ahoo.wow.compiler.metadata

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import me.ahoo.test.asserts.assert
import me.ahoo.wow.configuration.WOW_METADATA_RESOURCE_NAME
import me.ahoo.wow.configuration.WowMetadata
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.serialization.toObject
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import tools.jackson.databind.JsonNode
import kotlin.io.path.Path
import kotlin.io.path.readText

/**
 * The KSP side of the aggregate-level `spaced`/`owner` declarations and of the static tenant: the same resolution
 * and conflicts as the runtime, reported as compile errors, and the effective policy recorded in wow-metadata.json.
 */
@OptIn(ExperimentalCompilerApi::class)
class AggregatePolicyMetadataTest {
    companion object {
        private const val CONTEXT = """
            package policy

            import me.ahoo.wow.api.annotation.BoundedContext

            @BoundedContext(
                "policy",
                packageScopes = [BoundedContextMarker::class],
                aggregates = [BoundedContext.Aggregate("cart", tenantId = "%s")],
            )
            object BoundedContextMarker
        """

        private fun aggregate(annotations: String): String = """
            @file:Suppress("DEPRECATION")
            package policy

            import me.ahoo.wow.api.annotation.AggregateOwner
            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.AggregateRoute
            import me.ahoo.wow.api.annotation.OwnerPolicy
            import me.ahoo.wow.api.annotation.Spaced
            import me.ahoo.wow.api.annotation.StaticTenantId

            class AddItem(val id: String)
            class ItemAdded(val id: String)

            @AggregateRoot
            $annotations
            class Cart(val id: String) {
                fun onCommand(command: AddItem): ItemAdded = ItemAdded(command.id)
                fun onSourcing(event: ItemAdded) = Unit
            }
        """

        @JvmStatic
        fun matrix(): List<Arguments> = listOf(
            // spaced, owner: old only / new only / both equal / route without policy / neither
            Arguments.of("@AggregateRoute(spaced = true, owner = AggregateRoute.Owner.ALWAYS)", true, "ALWAYS"),
            Arguments.of("@Spaced @AggregateOwner(OwnerPolicy.AGGREGATE_ID)", true, "AGGREGATE_ID"),
            Arguments.of(
                "@Spaced @AggregateOwner(OwnerPolicy.ALWAYS) " +
                    "@AggregateRoute(spaced = true, owner = AggregateRoute.Owner.ALWAYS)",
                true,
                "ALWAYS"
            ),
            Arguments.of("@Spaced @AggregateOwner(OwnerPolicy.ALWAYS) @AggregateRoute(\"carts\")", true, "ALWAYS"),
            Arguments.of("@Spaced(false) @AggregateOwner(OwnerPolicy.NEVER)", null, null),
            Arguments.of("@AggregateRoute(\"carts\")", null, null),
            Arguments.of("", null, null),
        )

        @JvmStatic
        fun conflicts(): List<Arguments> = listOf(
            Arguments.of("@Spaced(false) @AggregateRoute(spaced = true)", "declares spaced twice"),
            Arguments.of(
                "@AggregateOwner(OwnerPolicy.ALWAYS) @AggregateRoute(owner = AggregateRoute.Owner.AGGREGATE_ID)",
                "declares its owner twice"
            ),
            Arguments.of(
                "@AggregateOwner(OwnerPolicy.NEVER) @AggregateRoute(owner = AggregateRoute.Owner.ALWAYS)",
                "declares its owner twice"
            ),
        )
    }

    private fun compile(contextTenantId: String, annotations: String): Pair<KotlinCompilation, JvmCompilationResult> {
        val compilation = KotlinCompilation().apply {
            inheritClassPath = true
            sources = listOf(
                SourceFile.kotlin("BoundedContextMarker.kt", CONTEXT.format(contextTenantId)),
                SourceFile.kotlin("Cart.kt", aggregate(annotations)),
            )
            configureKsp {
                symbolProcessorProviders += MetadataSymbolProcessorProvider()
            }
            jvmTarget = "17"
        }
        return compilation to compilation.compile()
    }

    private fun KotlinCompilation.cartNode(): JsonNode {
        val json = Path(kspSourcesDir.path, "resources", WOW_METADATA_RESOURCE_NAME).readText()
        json.toObject<WowMetadata>().contexts["policy"]!!.aggregates.assert().containsKey("cart")
        return json.toJsonNode<JsonNode>().path("contexts").path("policy").path("aggregates").path("cart")
    }

    @ParameterizedTest
    @MethodSource("matrix")
    fun `records the effective policy`(annotations: String, spaced: Boolean?, owner: String?) {
        val (compilation, result) = compile("", annotations)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val cart = compilation.cartNode()
        if (spaced == null) {
            cart.has("spaced").assert().isFalse()
        } else {
            cart.path("spaced").booleanValue().assert().isEqualTo(spaced)
        }
        if (owner == null) {
            cart.has("owner").assert().isFalse()
        } else {
            cart.path("owner").stringValue().assert().isEqualTo(owner)
        }
    }

    @ParameterizedTest
    @MethodSource("conflicts")
    fun `fails to compile on conflicting declarations`(annotations: String, message: String) {
        val (_, result) = compile("", annotations)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains(message, "policy.Cart")
    }

    @Test
    fun `static tenant declared once or equally compiles`() {
        compile("tenant-a", "@StaticTenantId(\"tenant-a\")").second.exitCode
            .assert().isEqualTo(KotlinCompilation.ExitCode.OK)
        val (compilation, result) = compile("tenant-a", "")
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.OK)
        compilation.cartNode().path("tenantId").stringValue().assert().isEqualTo("tenant-a")
    }

    @Test
    fun `static tenant declared twice with different values fails to compile`() {
        val (_, result) = compile("tenant-b", "@StaticTenantId(\"tenant-a\")")
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains("tenant-a", "tenant-b")
    }
}
