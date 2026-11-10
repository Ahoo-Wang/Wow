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
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Issue #5696: the annotations of a property a state or a command inherits are read from the declarations that carry
 * them, the supertype in the module's sources or a library. A property inherited with its type substituted (`List<T>`
 * as `List<String>`) is a member KSP gives without its field-targeted and constructor-parameter annotations, and an
 * override that leaves an annotation out still means what its supertype's declaration says. Each reader of a
 * property's annotations is held: the query fields' Jackson names, the access attributes and the aggregate id.
 */
@OptIn(ExperimentalCompilerApi::class)
class InheritedPropertiesTest {
    private val base = SourceFile.kotlin(
        "Base.kt",
        """
        package lib

        import com.fasterxml.jackson.annotation.JsonIgnore
        import com.fasterxml.jackson.annotation.JsonProperty
        import me.ahoo.wow.api.annotation.Access
        import me.ahoo.wow.api.annotation.AggregateId

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

            @Access
            val readers: List<T> = emptyList()

            @Access
            open val region: String = "eu"
        }

        abstract class Addressed<I : CharSequence>(@field:AggregateId val target: I)
        """.trimIndent(),
    )

    private val domain = SourceFile.kotlin(
        "Cart.kt",
        """
        @file:OptIn(me.ahoo.wow.api.annotation.InternalWowApi::class)

        package sample

        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.BoundedContext
        import me.ahoo.wow.spi.json.StateJson

        @BoundedContext(name = "shop")
        object Shop

        class Rename(target: String, val name: String) : lib.Addressed<String>(target)
        data class Renamed(val name: String)

        @AggregateRoot
        class Cart(private val state: CartState) {
            fun onCommand(command: Rename): Renamed = Renamed(command.name)
        }

        class CartState(val id: String) : lib.Base<String>() {
            // An override that leaves @Access out: still the access attribute its supertype declares.
            override val region: String = "us"

            fun onSourcing(event: Renamed) = Unit
        }

        fun serialized(): String = StateJson.mapper.writeValueAsString(CartState("c"))
        """.trimIndent(),
    )

    @Test
    fun `an inherited property should be read by its declarations, the supertype in the sources`() {
        assertReadByDeclarations(compileModel("generate", base, domain))
    }

    @Test
    fun `an inherited property should be read by its declarations, the supertype in a library`() {
        assertReadByDeclarations(compileModelAgainst("generate", listOf(compileLibrary(base)), domain))
    }

    private fun assertReadByDeclarations(compiled: Pair<KotlinCompilation, JvmCompilationResult>) {
        val (compilation, result) = compiled
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        // The query fields: the paths Jackson writes.
        val json = result.classLoader.loadClass("sample.CartKt").getMethod("serialized").invoke(null) as String
        json.assert().isEqualTo(
            "{\"id\":\"c\",\"firsts\":[],\"got\":[],\"kids\":[],\"label\":\"\",\"readers\":[],\"region\":\"us\"}",
        )
        result.paths("sample.CartStateProperties").assert().containsExactlyInAnyOrder(
            "state.id",
            "state.firsts",
            "state.kids",
            "state.got",
            "state.label",
            "state.readers",
            "state.region",
        )
        val context = compilation.fragment().contexts.single()
        // The access attributes: a substituted one and an override's.
        val cart = context.aggregates.single()
        cart.access.associate { it.name to it.multiple }.assert()
            .isEqualTo(mapOf("readers" to true, "region" to false))
        // The aggregate id: a substituted property's @field:AggregateId.
        context.command("sample.Rename").idProperty.assert().isEqualTo("target")
    }

    private fun ContextManifest.command(schema: String) =
        aggregates.single().commands.single { Manifest.schemaKey(it.schema) == schema }

    private fun KotlinCompilation.fragment(): Manifest {
        val file = workingDir.walkTopDown()
            .single { it.isFile && it.parentFile.invariantSeparatorsPath.endsWith(Manifest.FRAGMENT_DIRECTORY) }
        return ManifestTree.read(jacksonObjectMapper().readValue(file, Map::class.java))
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
