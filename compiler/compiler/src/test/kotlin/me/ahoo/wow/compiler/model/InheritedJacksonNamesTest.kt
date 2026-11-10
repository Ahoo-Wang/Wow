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
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test

/**
 * Issue #5696: a state's query fields name the properties it inherits as Jackson writes them, by the Jackson annotations
 * of the supertype's declarations, whether the supertype is in the module's sources or a library. A property whose type
 * names the supertype's type parameter is the case KSP gives without them.
 */
@OptIn(ExperimentalCompilerApi::class)
class InheritedJacksonNamesTest {
    private val base = SourceFile.kotlin(
        "Base.kt",
        """
        package lib

        import com.fasterxml.jackson.annotation.JsonIgnore
        import com.fasterxml.jackson.annotation.JsonProperty

        abstract class Base<T>(@JsonProperty("firsts") val leading: List<T> = emptyList()) {
            @field:JsonProperty("kids")
            val children: List<T> = emptyList()

            @get:JsonProperty("got")
            val gotten: List<T> = emptyList()

            @JsonIgnore
            val hidden: List<T> = emptyList()

            @field:JsonProperty("label")
            val title: String = ""

            @JsonIgnore
            val secret: String = ""
        }
        """.trimIndent(),
    )

    private val domain = SourceFile.kotlin(
        "Cart.kt",
        """
        @file:OptIn(me.ahoo.wow.api.annotation.InternalWowApi::class)

        package sample

        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate
        import me.ahoo.wow.spi.json.StateJson

        @CreateAggregate
        data class Open(val by: Int)
        data class Opened(val by: Int)

        @AggregateRoot
        class Cart(private val state: CartState) {
            fun onCommand(command: Open): Opened = Opened(command.by)
        }

        class CartState : lib.Base<String>() {
            fun onSourcing(event: Opened) = Unit
        }

        fun serialized(): String = StateJson.mapper.writeValueAsString(CartState())
        """.trimIndent(),
    )

    /** The paths Jackson writes, under `state.`. */
    private val written = listOf("state.firsts", "state.kids", "state.got", "state.label")

    @Test
    fun `an inherited property should be named as Jackson names it, the supertype in the sources`() {
        val (_, result) = compileModel("generate", base, domain)
        assertNamedAsWritten(result)
    }

    @Test
    fun `an inherited property should be named as Jackson names it, the supertype in a library`() {
        val (_, result) = compileModelAgainst("generate", listOf(compileLibrary(base)), domain)
        assertNamedAsWritten(result)
    }

    private fun assertNamedAsWritten(result: JvmCompilationResult) {
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val json = result.classLoader.loadClass("sample.CartKt").getMethod("serialized").invoke(null) as String
        json.assert().isEqualTo("{\"firsts\":[],\"got\":[],\"kids\":[],\"label\":\"\"}")
        result.paths("sample.CartStateProperties").assert().containsExactlyInAnyOrderElementsOf(written)
    }

    /** The paths of the generated object's own constants. */
    private fun JvmCompilationResult.paths(name: String): List<String> {
        val type = classLoader.loadClass(name)
        val instance = type.getField("INSTANCE").get(null)
        val queryField = classLoader.loadClass("me.ahoo.wow.api.query.QueryField")
        val path = queryField.getMethod("getPath")
        return type.declaredFields.filter { it.type == queryField }.map { field ->
            field.isAccessible = true
            path.invoke(field.get(instance)) as String
        }
    }
}
