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
import me.ahoo.wow.spi.json.WowJson
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test

/**
 * A Java bean state's query fields are the properties Wow's JSON writes of it (#6839): KSP shows its private fields as
 * properties and its getters as functions, and Jackson writes the getters. The getters and their names follow Jackson
 * 3's `DefaultAccessorNamingStrategy`, the same in 3.1 (Boot's) and 3.2 (springdoc's), Wow's two. Each test holds one
 * rule twice: the generated `<State>Properties` lists the fields it names, and [WowJson] writes those properties of an
 * instance, no more, so a Jackson that named them otherwise fails here.
 */
@OptIn(ExperimentalCompilerApi::class)
class JavaBeanGettersTest {
    private val root = SourceFile.kotlin(
        "Cart.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate

        @CreateAggregate
        data class Create(val owner: String)
        data class Created(val owner: String)

        @AggregateRoot
        class Cart(private val state: CartState) {
            fun onCommand(command: Create): Created = Created(command.owner)
        }
        """.trimIndent(),
    )

    /**
     * The query fields of the Java state whose class body is [members], and the properties [WowJson] writes of an
     * instance of it, which the rule's test expects to be one set.
     */
    private fun fieldsOf(members: String): Pair<Set<String>, Set<String>> = stateFieldsOf(
        SourceFile.java(
            "CartState.java",
            """
            package sample;

            import com.fasterxml.jackson.annotation.*;
            import java.util.concurrent.atomic.AtomicBoolean;

            @JsonIgnoreProperties({"derived", "shown"})
            public class CartState {
            $members

                public void onSourcing(Created event) { }
            }
            """.trimIndent(),
        ),
    )

    /** The query fields of the state `sample.CartState` of [sources], and the properties [WowJson] writes of one. */
    private fun stateFieldsOf(vararg sources: SourceFile): Pair<Set<String>, Set<String>> {
        val (compilation, result) = compileModel("generate", ManifestFragmentFixtures.context, root, *sources)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val properties = compilation.workingDir.walkTopDown().single { it.name == "CartStateProperties.kt" }.readText()
        val fields = QUERY_FIELD.findAll(properties).map { it.groupValues[1] }.toSet()
        val instance = result.classLoader.loadClass("sample.CartState").getDeclaredConstructor().newInstance()
        val written = WowJson.mapper.readValue(WowJson.mapper.writeValueAsString(instance), Map::class.java).keys
        return fields to written.map { it.toString() }.toSet()
    }

    private fun assertFields(members: String, vararg expected: String) {
        val (fields, written) = fieldsOf(members)
        fields.assert().containsExactlyInAnyOrder(*expected)
        written.assert().containsExactlyInAnyOrder(*expected)
    }

    @Test
    fun `a Java bean state's getters should be its query fields`() {
        // A getter of a private field, and one of no field, a computed property.
        assertFields(
            """
            private String owner = "alice";
            public String getOwner() { return owner; }
            public String getLabel() { return owner + "'s cart"; }
            """,
            "owner",
            "label",
        )
    }

    @Test
    fun `an is getter should read only a boolean, a Boolean or an AtomicBoolean`() {
        assertFields(
            """
            private boolean open = true;
            public boolean isOpen() { return open; }
            public Boolean isPaid() { return Boolean.TRUE; }
            public AtomicBoolean isLocked() { return new AtomicBoolean(); }
            public String isNamed() { return "alice"; }
            """,
            "open",
            "paid",
            "locked",
        )
    }

    @Test
    fun `a getter's name should start with an upper-case letter after its prefix`() {
        assertFields(
            """
            public String getOwner() { return "alice"; }
            public String getter() { return "alice"; }
            public String getaway() { return "alice"; }
            public String get_value() { return "alice"; }
            public String get() { return "alice"; }
            public boolean is() { return true; }
            """,
            "owner",
        )
    }

    @Test
    fun `a property should be its name with its first letter lowered, unless its second letter is upper-case too`() {
        assertFields(
            """
            public String getFullName() { return "alice"; }
            public String getURL() { return "alice"; }
            public int getN() { return 1; }
            public int getX1() { return 1; }
            """,
            "fullName",
            "URL",
            "n",
            "x1",
        )
    }

    @Test
    fun `a static, void, non-public or parameterized method should be no getter`() {
        assertFields(
            """
            public String getOwner() { return "alice"; }
            public static String getVersion() { return "1"; }
            public void getNothing() { }
            String getPackaged() { return "alice"; }
            protected String getGuarded() { return "alice"; }
            private String getHidden() { return "alice"; }
            public String getItem(int index) { return "alice"; }
            """,
            "owner",
        )
    }

    @Test
    fun `a property should be named and left out by the Jackson annotations on its field or its getter`() {
        assertFields(
            """
            @JsonProperty("who") private String owner = "alice";
            private long total;
            private String secret = "s";
            @JsonIgnore private String hidden = "h";
            public String getOwner() { return owner; }
            @JsonProperty("sum") public long getTotal() { return total; }
            @JsonIgnore public String getSecret() { return secret; }
            public String getHidden() { return hidden; }
            public String getDerived() { return owner; }
            @JsonProperty("shown") public String getMasked() { return secret; }
            @JsonIgnore public String getComputed() { return owner; }
            """,
            "who",
            "sum",
        )
    }

    @Test
    fun `a Kotlin state's Java bean base should give its getters as query fields`() {
        // The state is Kotlin, its base a Java bean: the base's private field read by its getter, and a computed getter.
        // KSP gives the state no private field of its base, so both are the base's Java getters.
        val base = SourceFile.java(
            "CartBase.java",
            """
            package sample;

            public class CartBase {
                private String owner = "alice";
                public String getOwner() { return owner; }
                public String getLabel() { return owner + "'s cart"; }
            }
            """.trimIndent(),
        )
        val state = SourceFile.kotlin(
            "CartState.kt",
            """
            package sample

            class CartState : CartBase() {
                val note: String = "n"

                fun onSourcing(event: Created) = Unit
            }
            """.trimIndent(),
        )
        val (fields, written) = stateFieldsOf(base, state)
        fields.assert().containsExactlyInAnyOrder("note", "owner", "label")
        written.assert().containsExactlyInAnyOrder("note", "owner", "label")
    }

    @Test
    fun `a base class's private field should be named and left out by its Jackson annotations, under any state`() {
        // A Java state and a Kotlin one: KSP gives neither the base's private fields, read from the base (#6974).
        val base = SourceFile.java(
            "CartBase.java",
            """
            package sample;

            import com.fasterxml.jackson.annotation.*;

            public class CartBase {
                @JsonProperty("who") private String owner = "alice";
                @JsonIgnore private String hidden = "h";
                private long total;
                public String getOwner() { return owner; }
                public String getHidden() { return hidden; }
                public long getTotal() { return total; }
            }
            """.trimIndent(),
        )
        val java = SourceFile.java(
            "CartState.java",
            """
            package sample;

            public class CartState extends CartBase {
                private String note = "n";
                public String getNote() { return note; }
                public void onSourcing(Created event) { }
            }
            """.trimIndent(),
        )
        val kotlin = SourceFile.kotlin(
            "CartState.kt",
            """
            package sample

            class CartState : CartBase() {
                val note: String = "n"

                fun onSourcing(event: Created) = Unit
            }
            """.trimIndent(),
        )
        listOf(java, kotlin).forEach { state ->
            val (fields, written) = stateFieldsOf(base, state)
            fields.assert().containsExactlyInAnyOrder("note", "who", "total")
            written.assert().containsExactlyInAnyOrder("note", "who", "total")
        }
    }

    private companion object {
        val QUERY_FIELD = Regex("QueryField\\.of\\(\"state\\.([^\"]+)\"\\)")
    }
}
