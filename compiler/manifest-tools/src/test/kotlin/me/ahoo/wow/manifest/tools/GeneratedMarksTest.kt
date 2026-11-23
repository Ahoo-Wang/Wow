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

package me.ahoo.wow.manifest.tools

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import me.ahoo.wow.manifest.copyWith
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test

/**
 * Every class the contract tools generate carries `@WowGenerated`, which coverage tools exclude generated code by: the
 * context object and each object nested in it (the aggregate's, its `Commands`, the `Wait`, each `Errors`), the
 * state's view and each class and enumeration it copies. The generated sources declare no function a class is
 * synthesized for, so every class compiled from them is marked.
 */
@OptIn(ExperimentalCompilerApi::class)
class GeneratedMarksTest {
    enum class Status { OPEN, CLOSED }

    /** A class of the domain, not on the api's class path: the view copies it. */
    data class Address(val city: String)

    class CartState(val id: String) {
        var status: Status = Status.OPEN
        var address: Address? = null
    }

    private fun contract(): Manifest {
        val context = ContextManifest(
            "shop",
            aggregates = listOf(
                AggregateManifest(
                    "cart",
                    state = StateManifest(Manifest.schemaRef(CartState::class.java.name)),
                    commands = listOf(CommandManifest("Add", schema = Manifest.schemaRef("shop.api.Add"))),
                ),
            ),
            errors = listOf(ErrorManifest("shop.cart.full", 409), ErrorManifest("shop.closed", 409)),
            subscriptions = listOf(
                SubscriptionManifest(
                    "CartSaga.onAdded",
                    SubscriptionKind.SAGA,
                    SubscriptionSource(aggregates = listOf("cart")),
                    FunctionManifest(FunctionKind.SAGA, "shop", "CartSaga", "onAdded"),
                ),
            ),
        )
        val fragment = Manifest(contexts = listOf(context))
            .copyWith(defs = mapOf("shop.api.Add" to mapOf("type" to "object")))
        return SchemaIntrospector(javaClass.classLoader).complete(fragment)
    }

    @Test
    fun `every class the contract tools generate should carry WowGenerated`() {
        val sources = ContractSources.of(contract(), mapOf("shop" to "shop.api"), queryFields = true) {
            it.startsWith("java.")
        }
        val result = KotlinCompilation().apply {
            inheritClassPath = true
            this.sources = sources.map { SourceFile.kotlin(it.path.substringAfterLast('/'), it.text) }
            jvmTarget = "21"
        }.compile()
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val marks = ClassMarks.of(result.outputDirectory).mapKeys { it.key.name }
        marks.keys.assert().contains(
            "shop.api.ShopContract",
            "shop.api.ShopContract\$Wait",
            "shop.api.ShopContract\$Errors",
            "shop.api.ShopContract\$Cart",
            "shop.api.ShopContract\$Cart\$Commands",
            "shop.api.ShopContract\$Cart\$Errors",
            "shop.api.CartStateView",
            "shop.api.CartStateView\$Address",
            "shop.api.CartStateView\$Status",
        )
        marks.filterValues { !it }.keys.assert().isEmpty()
    }
}
