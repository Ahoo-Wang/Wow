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
import me.ahoo.wow.manifest.Manifest
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test

/**
 * A command's `id` it inherits, an interface's default getter, an interface's property it implements, a superclass's
 * property, a Java getter or a record's accessor, is warned of where it is not marked `@AggregateId`, whether the
 * command is compiled with its root or, as a context's commands are, in its api module; marked, it is the id the
 * generated model reads. An `id` the mark cannot take, one Wow's JSON does not write or the model cannot read, which
 * 9.x bound by reflection all the same, is warned of too, saying why.
 */
@OptIn(ExperimentalCompilerApi::class)
class InheritedAggregateIdTest {
    private val context = SourceFile.kotlin(
        "ShopContext.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.BoundedContext

        @BoundedContext(name = "shop")
        object ShopContext
        """.trimIndent(),
    )

    private val commands = SourceFile.kotlin(
        "Commands.kt",
        """
        package sample

        import com.fasterxml.jackson.annotation.JsonIgnore
        import com.fasterxml.jackson.annotation.JsonIgnoreProperties
        import com.fasterxml.jackson.annotation.JsonProperty
        import me.ahoo.wow.api.annotation.AggregateId

        interface Keyed {
            val sku: String
            val id: String get() = sku
        }
        interface Identifier {
            val id: String
        }
        abstract class Base(val id: String)
        abstract class MarkedBase(@AggregateId val id: String)

        data class Recount(override val sku: String) : Keyed
        data class Reserve(override val id: String, val quantity: Int) : Identifier
        class Release(id: String) : Base(id)
        data class RecountMarked(override val sku: String) : Keyed {
            @AggregateId
            override val id: String get() = super.id
        }
        data class ReserveMarked(@AggregateId override val id: String) : Identifier
        class ReleaseMarked(id: String) : MarkedBase(id)
        // An id 9.x bound by reflection, which the mark cannot take: Wow's JSON does not write it, or the model cannot
        // read it.
        data class Hidden(@JsonIgnore val id: String, private val secret: String)
        class Kept(private val id: String)
        open class Guarded(protected val id: String)
        @JsonIgnoreProperties("id")
        data class Dropped(val id: String)
        @JsonIgnoreProperties("ref")
        data class Referenced(@JsonProperty("ref") val id: String)
        """.trimIndent(),
    )

    private val java = listOf(
        SourceFile.java(
            "Audit.java",
            """
            package sample;

            public class Audit {
                private String id;

                public String getId() {
                    return id;
                }
            }
            """.trimIndent(),
        ),
        SourceFile.java(
            "Ledger.java",
            """
            package sample;

            public class Ledger {
                public String id;

                public String getId() {
                    return id;
                }
            }
            """.trimIndent(),
        ),
        // A getter of the package only: Wow's JSON does not write its id.
        SourceFile.java(
            "Sealed.java",
            """
            package sample;

            public class Sealed {
                private String id;

                String getId() {
                    return id;
                }
            }
            """.trimIndent(),
        ),
        SourceFile.java("Tally.java", "package sample;\n\npublic record Tally(String id) {\n}\n"),
        // Records of no String id: nothing to report.
        SourceFile.java("Count.java", "package sample;\n\npublic record Count(int id) {\n}\n"),
        SourceFile.java("Note.java", "package sample;\n\npublic record Note(String text) {\n}\n"),
        SourceFile.java(
            "AuditMarked.java",
            """
            package sample;

            import me.ahoo.wow.api.annotation.AggregateId;

            public class AuditMarked {
                @AggregateId
                private String id;

                public String getId() {
                    return id;
                }
            }
            """.trimIndent(),
        ),
    )

    private val names = listOf(
        "Recount", "Reserve", "Release", "Audit", "RecountMarked", "ReserveMarked", "ReleaseMarked", "AuditMarked",
        "Hidden", "Kept", "Guarded", "Dropped", "Referenced", "Ledger", "Sealed", "Tally", "Count", "Note",
    )

    private val root = SourceFile.kotlin(
        "Stock.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AggregateRoot

        data class Stocked(val quantity: Int)

        @AggregateRoot
        class Stock(val id: String) {
            ${names.joinToString("\n    ") { "fun onCommand(command: $it): Stocked = Stocked(0)" }}
            fun onSourcing(event: Stocked) = Unit
        }
        """.trimIndent(),
    )

    private fun assertInherited(compilation: KotlinCompilation, result: JvmCompilationResult, elsewhere: String) {
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val warned = mapOf(
            "Recount" to "inherited from the interface sample.Keyed's default getter",
            "Reserve" to "declared by sample.Reserve",
            "Release" to "inherited from the class sample.Base",
            "Audit" to "read by sample.Audit's getter getId()",
            "Ledger" to "declared by sample.Ledger",
        )
        warned.forEach { (name, origin) ->
            result.messages.assert().contains(
                "command $name has a String property id ($origin$elsewhere) and no @AggregateId property",
            )
        }
        // Reported as well, saying the mark alone will not do: 9.x bound them by reflection.
        val unmarkable = mapOf(
            "Hidden" to "not written by Wow's JSON",
            "Dropped" to "not written by Wow's JSON",
            "Referenced" to "not written by Wow's JSON",
            "Kept" to "not visible to the generated model",
            "Guarded" to "not visible to the generated model",
            "Sealed" to "not visible to the generated model",
        )
        unmarkable.forEach { (name, hindrance) ->
            result.messages.assert().contains(
                "command $name has a String property id (declared by sample.$name$elsewhere, $hindrance) and no " +
                    "@AggregateId property: Wow 10 does not take a command's aggregate from its body unless a property " +
                    "is marked @AggregateId. If id names $name's aggregate, make it a public property Wow's JSON " +
                    "writes, then mark it @AggregateId",
            )
        }
        // A record's component: KSP shows it as its accessor alone, and a mark on it as none.
        result.messages.assert().contains(
            "command Tally has a String record component id (read by sample.Tally's accessor id()$elsewhere) and no " +
                "@AggregateId property",
            "mark the component @AggregateId (record Tally(@AggregateId String id, …))",
        )
        names.filterNot { it in warned || it in unmarkable || it == "Tally" }
            .forEach { result.messages.assert().doesNotContain("command $it has") }
        val model = compilation.workingDir.walkTopDown().single { it.name == "StockModel.kt" }.readText()
        val marked = listOf("RecountMarked", "ReserveMarked", "ReleaseMarked", "AuditMarked")
        marked.forEach { model.assert().contains("aggregateId<sample.$it> { it.`id` }") }
        val fragment = compilation.workingDir.walkTopDown()
            .single { it.isFile && it.parentFile.invariantSeparatorsPath.endsWith(Manifest.FRAGMENT_DIRECTORY) }
        val manifest = ManifestFragmentFixtures.read(fragment).contexts.single().aggregates.single().commands
            .associateBy { it.name }
        marked.forEach { manifest.getValue(it).idProperty.assert().describedAs(it).isEqualTo("id") }
        warned.keys.forEach { manifest.getValue(it).idProperty.assert().describedAs(it).isNull() }
    }

    @Test
    fun `an inherited id should be warned of unmarked and read marked, compiled with its root`() {
        val (compilation, result) = compileModel("generate", context, commands, *java.toTypedArray(), root)
        assertInherited(compilation, result, elsewhere = "")
    }

    @Test
    fun `a record's component should take the mark, compiled here or in a library`() {
        val marked = SourceFile.java(
            "Tally.java",
            """
            package sample;

            import me.ahoo.wow.api.annotation.AggregateId;
            import me.ahoo.wow.api.annotation.CreateAggregate;

            @CreateAggregate
            public record Tally(@AggregateId String id, int quantity) {
            }
            """.trimIndent(),
        )
        val stock = SourceFile.kotlin(
            "Stock.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot

            data class Stocked(val quantity: Int)

            @AggregateRoot
            class Stock(val id: String) {
                fun onCommand(command: Tally): Stocked = Stocked(command.quantity())
                fun onSourcing(event: Stocked) = Unit
            }
            """.trimIndent(),
        )
        val library = compileLibrary(marked)
        listOf(
            compileModel("generate", context, marked, stock),
            compileModelAgainst("generate", listOf(library), context, stock),
        ).forEach { (compilation, result) ->
            result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
            result.messages.assert().doesNotContain("command Tally has")
            compilation.workingDir.walkTopDown().single { it.name == "StockModel.kt" }.readText()
                .assert().contains("aggregateId<sample.Tally> { it.`id` }")
            val fragment = compilation.workingDir.walkTopDown()
                .single { it.isFile && it.parentFile.invariantSeparatorsPath.endsWith(Manifest.FRAGMENT_DIRECTORY) }
            ManifestFragmentFixtures.read(
                fragment
            ).contexts.single().aggregates.single().commands.single { it.name == "Tally" }
                .idProperty.assert().isEqualTo("id")
        }
    }

    /** The record command [name] of [source], with the [extra] sources, and a `Stock` that takes it. */
    private fun recordOf(name: String, source: String, vararg extra: SourceFile) = compileModel(
        "generate",
        context,
        *extra,
        SourceFile.java("$name.java", source.trimIndent()),
        SourceFile.kotlin(
            "Stock.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot

            data class Counted(val quantity: Int)

            @AggregateRoot
            class Stock(val id: String) {
                fun onCommand(command: $name): Counted = Counted(0)
                fun onSourcing(event: Counted) = Unit
            }
            """.trimIndent(),
        ),
    )

    @Test
    fun `a record's canonical constructor should be the one of its components, beside a wider one`() {
        val (compilation, result) = recordOf(
            "Count",
            """
            package sample;

            import me.ahoo.wow.api.annotation.AggregateId;

            public record Count(@AggregateId String id, int quantity) {
                public Count(String id, int quantity, String note) {
                    this(id, quantity);
                }
            }
            """,
        )
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        result.messages.assert().doesNotContain("command Count has")
        compilation.workingDir.walkTopDown().single { it.name == "StockModel.kt" }.readText()
            .assert().contains("aggregateId<sample.Count> { it.`id` }")
    }

    @Test
    fun `a marked record component should be refused as a marked property is`() {
        mapOf(
            // No String.
            """
            package sample;

            import me.ahoo.wow.api.annotation.AggregateId;

            public record Count(@AggregateId long id) {
            }
            """ to "command Count's @AggregateId record component id is a kotlin.Long: an aggregate id is a String",
            // An unaddressed command, whose transformer finds its aggregate.
            """
            package sample;

            import me.ahoo.wow.api.annotation.AggregateId;
            import me.ahoo.wow.api.annotation.Unaddressed;

            @Unaddressed
            public record Count(@AggregateId String id) {
            }
            """ to "command Count is @Unaddressed and has an @AggregateId property: a transformer finds an unaddressed " +
                "command's aggregate, and the body names an @AggregateId command's",
            // Beside another marked property, an interface's.
            """
            package sample;

            import me.ahoo.wow.api.annotation.AggregateId;

            public record Count(@AggregateId String id, String key) implements Keyed {
                @Override
                public String getKey() {
                    return key;
                }
            }
            """ to "command Count has several @AggregateId properties (key, id): one property holds its aggregate's id",
        ).forEach { (source, problem) ->
            val keyed = SourceFile.kotlin(
                "Keyed.kt",
                "package sample\n\nimport me.ahoo.wow.api.annotation.AggregateId\n\n" +
                    "interface Keyed {\n    @AggregateId\n    val key: String\n}\n",
            )
            val (_, result) = recordOf("Count", source, keyed)
            result.exitCode.assert().describedAs(problem).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
            result.messages.assert().contains(problem)
        }
    }

    @Test
    fun `the mark on a function that is no record's component should be a compile error naming where it goes`() {
        val marked = SourceFile.kotlin(
            "Count.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateId
            import me.ahoo.wow.api.annotation.AggregateRoot

            data class Count(val stock: String) {
                @AggregateId
                fun target(): String = stock
            }

            data class Counted(val quantity: Int)

            @AggregateRoot
            class Stock(val id: String) {
                fun onCommand(command: Count): Counted = Counted(0)
                fun onSourcing(event: Counted) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", context, marked)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains(
            "function sample.Count.target is annotated @AggregateId, which only a property of a command an " +
                "aggregate decides takes: a Kotlin property, a Java field read by its public getter, or a Java " +
                "record's component",
        )
    }

    @Test
    fun `an inherited id should be warned of unmarked and read marked, compiled in the api module`() {
        val api = compileLibrary(commands, *java.toTypedArray())
        val (compilation, result) = compileModelAgainst("generate", listOf(api), context, root)
        assertInherited(compilation, result, elsewhere = ", compiled in another module")
    }
}
