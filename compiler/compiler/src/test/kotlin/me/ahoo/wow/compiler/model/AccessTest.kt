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
import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.manifest.AccessAttributeManifest
import me.ahoo.wow.manifest.AccessAttributeManifest.Type
import me.ahoo.wow.manifest.AccessAttributeName
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

/** `@Access`: the access attributes the model processor finds on a state, checks, generates and writes. */
@OptIn(ExperimentalCompilerApi::class)
class AccessTest {
    /** A document whose state declares [members] (Kotlin source inside the state class), and [extra] beside it. */
    private fun document(members: String, root: String = "", extra: String = ""): SourceFile = SourceFile.kotlin(
        "Document.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.Access
        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.Blocking
        import me.ahoo.wow.api.annotation.BoundedContext
        import me.ahoo.wow.api.annotation.CreateAggregate

        @BoundedContext(name = "office")
        object OfficeContext

        enum class Level { LOW, HIGH }

        @CreateAggregate
        data class Draft(val department: String, val sharedWith: Set<String>)
        data class Drafted(val department: String, val sharedWith: Set<String>)

        @AggregateRoot
        class Document(private val state: DocumentState) {
            fun onCommand(command: Draft): Drafted = Drafted(command.department, command.sharedWith)
            $root
        }

        class DocumentState {
            var department: String = ""
            var sharedWith: Set<String> = emptySet()
            var orgPath: String? = null
            var level: Level = Level.HIGH
            fun onSourcing(event: Drafted) {
                department = event.department
                sharedWith = event.sharedWith
            }
            $members
        }

        $extra
        """.trimIndent(),
    )

    /** Runs the generated model: the access attributes of a document drafted for sales, shared with bob. */
    private fun scenario(): SourceFile = SourceFile.kotlin(
        "DocumentScenario.kt",
        """
        @file:OptIn(me.ahoo.wow.api.annotation.InternalWowApi::class)

        package sample

        import kotlinx.coroutines.runBlocking
        import me.ahoo.wow.api.decide.Command
        import me.ahoo.wow.kernel.CommandOutcome
        import me.ahoo.wow.kernel.Kernel
        import me.ahoo.wow.kernel.memory.InMemoryEventLog

        fun access(): Map<String, Any?> = runBlocking {
            val model = documentModel("office")
            val ref = model.type.ref("tenant", "d-1")
            val outcome = Kernel(InMemoryEventLog()).execute(model, Command("c", "r", ref, Draft("sales", setOf("bob"))))
            model.accessOf((outcome as CommandOutcome.Committed).state as DocumentState)
        }
        """.trimIndent(),
    )

    private class Compiled(val compilation: KotlinCompilation, val result: JvmCompilationResult) {
        fun source(name: String): String = compilation.workingDir.walkTopDown().single { it.name == name }.readText()

        fun fragment(): Manifest {
            val file = compilation.workingDir.walkTopDown()
                .filter { it.isFile && it.parentFile.invariantSeparatorsPath.endsWith(Manifest.FRAGMENT_DIRECTORY) }
                .single()
            return ManifestTree.read(jacksonObjectMapper().readValue(file, Map::class.java), ManifestTree.REFUSE)
        }
    }

    private fun compiled(vararg sources: SourceFile): Compiled {
        val (compilation, result) = compileModel("generate", *sources)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        return Compiled(compilation, result)
    }

    private fun failing(members: String, root: String = "", extra: String = ""): String {
        val (_, result) = compileModel("generate", document(members, root, extra))
        result.exitCode.assert().withFailMessage { members }.isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        return result.messages
    }

    @Test
    fun `the access attributes are generated, derived by the model and written into the Manifest`() {
        val compiled = compiled(
            document(
                """
                @Access val departmentName: String get() = department
                @Access("shared") fun shares(): Set<String> = sharedWith
                @Access("org") val path: String? get() = orgPath
                @Access val clearance: Level get() = level
                @Access val pages: Int get() = 3
                @Access val words: Long get() = 7L
                @Access val published: Boolean get() = false
                @Access val reviewers: List<Level> get() = listOf(level)
                """.trimIndent(),
            ),
            scenario(),
        )
        compiled.result.classLoader.loadClass("sample.DocumentScenarioKt").getMethod("access").invoke(null)
            .assert().isEqualTo(
                mapOf(
                    "departmentName" to "sales",
                    "org" to null,
                    "clearance" to compiled.result.classLoader.loadClass("sample.Level").enumConstants[1],
                    "pages" to 3,
                    "words" to 7L,
                    "published" to false,
                    "reviewers" to setOf(compiled.result.classLoader.loadClass("sample.Level").enumConstants[1]),
                    "shared" to setOf("bob"),
                ),
            )
        val model = compiled.source("DocumentModel.kt")
        model.assert()
            .contains("access(sample.DocumentStateAccess.DEPARTMENT_NAME) { state -> state.departmentName }")
            .contains("access(sample.DocumentStateAccess.SHARED) { state -> state.shares() }")
            .contains("access(sample.DocumentStateAccess.ORG) { state -> state.path }")
        assertReferences(compiled.source("DocumentStateAccess.kt"))
        compiled.fragment().contexts.single().aggregates.single().access.assert().containsExactly(
            AccessAttributeManifest("departmentName", Type.STRING),
            AccessAttributeManifest("org", Type.STRING),
            AccessAttributeManifest("clearance", Type.STRING),
            AccessAttributeManifest("pages", Type.INTEGER),
            AccessAttributeManifest("words", Type.INTEGER),
            AccessAttributeManifest("published", Type.BOOLEAN),
            AccessAttributeManifest("reviewers", Type.STRING, multiple = true),
            AccessAttributeManifest("shared", Type.STRING, multiple = true),
        )
    }

    /** The references of the attributes, typed by the state, in `<State>Access`. */
    private fun assertReferences(references: String) {
        val attribute = "me.ahoo.wow.api.access.AccessAttribute"
        references.assert()
            .contains(
                "public object DocumentStateAccess : me.ahoo.wow.api.access.AccessAttributes<sample.DocumentState>() {"
            )
            .contains(
                "public val DEPARTMENT_NAME: $attribute.Single<sample.DocumentState, kotlin.String> = " +
                    "$attribute.single(\"departmentName\", kotlin.String::class.javaObjectType)",
            )
            .contains(
                "public val SHARED: $attribute.Multiple<sample.DocumentState, kotlin.String> = " +
                    "$attribute.multiple(\"shared\", kotlin.String::class.javaObjectType)",
            )
            .contains("public val CLEARANCE: $attribute.Single<sample.DocumentState, sample.Level>")
            .contains("public val PAGES: $attribute.Single<sample.DocumentState, kotlin.Int>")
    }

    @Test
    fun `a state without access attributes generates no reference object, no call and no Manifest section`() {
        val compiled = compiled(document(""))
        compiled.source("DocumentModel.kt").assert().doesNotContain("access(")
        compiled.compilation.workingDir.walkTopDown().none { it.name == "DocumentStateAccess.kt" }.assert().isTrue()
        compiled.fragment().contexts.single().aggregates.single().access.assert().isEmpty()
    }

    @Test
    fun `the references of an internal state, or of an internal enum's values, are internal`() {
        val compiled = compiled(
            document(
                """
                @Access internal val tier: Tier get() = Tier.GOLD
                @Access internal val code: Code get() = Code(department)
                @Access internal val rank: Rank get() = Rank(Tier.GOLD)
                """.trimIndent(),
                extra = """
                internal enum class Tier { GOLD }
                @JvmInline internal value class Code(val value: String)
                @JvmInline value class Rank internal constructor(internal val tier: Tier)
                """.trimIndent(),
            ),
        )
        val single = "me.ahoo.wow.api.access.AccessAttribute.Single<sample.DocumentState"
        compiled.source("DocumentStateAccess.kt").assert()
            .contains("internal object DocumentStateAccess")
            .contains("internal val TIER: $single, sample.Tier>")
            // An internal value class, and a value class of an internal enum: each internal.
            .contains("internal val CODE: $single, kotlin.String>")
            .contains("internal val RANK: $single, sample.Tier>")
    }

    @Test
    fun `an access attribute of another type is a compile error`() {
        listOf(
            "Double" to "1.0",
            "java.math.BigDecimal" to "java.math.BigDecimal.ONE",
            "Map<String, String>" to "mapOf()",
            "Set<String>?" to "null",
            "List<String?>" to "listOf()",
            "List<List<String>>" to "listOf()",
            "List<*>" to "listOf<String>()",
            "Array<String>" to "arrayOf()",
            "Any" to "\"\"",
        ).forEach { (type, value) ->
            failing("@Access val wrong: $type get() = $value").assert()
                .contains("@Access property wrong returns")
                .contains(
                    "not a String, Int, Long, Boolean or enum, a value class wrapping one, or a List, Set or Collection"
                )
        }
    }

    @Test
    fun `a nullable single value is an attribute that may be absent`() {
        compiled(document("@Access val maybe: Level? get() = null")).fragment().contexts.single().aggregates.single()
            .access.assert().containsExactly(AccessAttributeManifest("maybe", Type.STRING))
    }

    @Test
    fun `a value class wrapping an allowed value is published as the value it wraps`() {
        val compiled = compiled(
            document(
                """
                @Access val departmentId: DepartmentId get() = DepartmentId(department)
                @Access val reviewer: DepartmentId? get() = null
                @Access val readers: Set<DepartmentId> get() = sharedWith.map(::DepartmentId).toSet()
                @Access val grade: Grade get() = Grade(level)
                """.trimIndent(),
                extra = """
                @JvmInline value class DepartmentId(val value: String)
                @JvmInline value class Grade(val level: Level)
                """.trimIndent(),
            ),
            scenario(),
        )
        val high = compiled.result.classLoader.loadClass("sample.Level").enumConstants[1]
        compiled.result.classLoader.loadClass("sample.DocumentScenarioKt").getMethod("access").invoke(null)
            .assert().isEqualTo(
                mapOf("departmentId" to "sales", "reviewer" to null, "readers" to setOf("bob"), "grade" to high),
            )
        compiled.source("DocumentModel.kt").assert()
            .contains("access(sample.DocumentStateAccess.DEPARTMENT_ID) { state -> state.departmentId.value }")
            .contains("access(sample.DocumentStateAccess.REVIEWER) { state -> state.reviewer?.value }")
            .contains("access(sample.DocumentStateAccess.READERS) { state -> state.readers.map { it.value } }")
            .contains("access(sample.DocumentStateAccess.GRADE) { state -> state.grade.level }")
        compiled.source("DocumentStateAccess.kt").assert()
            .contains(
                "public val DEPARTMENT_ID: me.ahoo.wow.api.access.AccessAttribute.Single<sample.DocumentState, kotlin.String>"
            )
            .contains(
                "public val READERS: me.ahoo.wow.api.access.AccessAttribute.Multiple<sample.DocumentState, kotlin.String>"
            )
            .contains(
                "public val GRADE: me.ahoo.wow.api.access.AccessAttribute.Single<sample.DocumentState, sample.Level>"
            )
        compiled.fragment().contexts.single().aggregates.single().access.assert().containsExactly(
            AccessAttributeManifest("departmentId", Type.STRING),
            AccessAttributeManifest("reviewer", Type.STRING),
            AccessAttributeManifest("readers", Type.STRING, multiple = true),
            AccessAttributeManifest("grade", Type.STRING),
        )
    }

    @Test
    fun `a value class of a library's class path is unwrapped too`() {
        val library = compileLibrary(
            SourceFile.kotlin(
                "Ids.kt",
                """
                package sample.ids

                @JvmInline value class TeamId(val value: Long)
                """.trimIndent(),
            ),
        )
        val (compilation, result) = compileModelAgainst(
            "generate",
            listOf(library),
            document("@Access val team: sample.ids.TeamId get() = sample.ids.TeamId(7)"),
        )
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        Compiled(compilation, result).source("DocumentModel.kt").assert()
            .contains("access(sample.DocumentStateAccess.TEAM) { state -> state.team.value }")
    }

    @Test
    fun `a value class wrapping anything else, or hiding what it wraps, is a compile error`() {
        mapOf(
            "@JvmInline value class Amount(val value: Double)" to "Amount",
            "@JvmInline value class Secret(private val value: String)" to "Secret",
            "@JvmInline value class Maybe(val value: String?)" to "Maybe",
            "@JvmInline value class Nested(val value: Inner)\n@JvmInline value class Inner(val value: String)" to "Nested",
        ).forEach { (declaration, type) ->
            failing("@Access val wrong: $type get() = TODO()", extra = declaration).assert()
                .contains("@Access property wrong returns $type, not")
        }
    }

    @Test
    fun `a misplaced or impure access member is a compile error`() {
        mapOf(
            "@Access fun withParameter(prefix: String): String = prefix" to
                "@Access function withParameter takes parameters: it reads the state only",
            "@Access suspend fun later(): String = department" to
                "@Access function later suspends: deriving an access attribute does no I/O",
            "@Access @Blocking fun blocking(): String = department" to
                "@Access function blocking is annotated @Blocking: deriving an access attribute does no I/O",
            "@Access private fun hidden(): String = department" to "@Access function hidden is private",
            "@Access private val secret: String get() = department" to "@Access property secret is private",
            "@Access val Int.extended: String get() = toString()" to "@Access property extended is an extension",
            "@Access fun String.extension(): String = this" to "@Access function extension is an extension",
        ).forEach { (member, problem) -> failing(member).assert().contains(problem) }
    }

    @Test
    fun `a name that is not lower camel case, built in or taken twice is a compile error`() {
        failing("@Access(\"org_path\") val path: String get() = department").assert()
            .contains(
                "@Access property path: access attribute name \"org_path\" is not valid: an access attribute's name is lower camel case"
            )
        failing("@Access(\"${"x".repeat(41)}\") val long: String get() = department").assert()
            .contains("of at most 40 characters")
        failing("@Access(\"owner\") val creator: String get() = department").assert()
            .contains("@Access property creator: access attribute name \"owner\" is a built-in attribute's")
        failing(
            """
            @Access val orgPath2: String get() = department
            @Access("orgpath2") fun other(): String = department
            """.trimIndent(),
        ).assert().contains("declares the access attributes orgPath2, orgpath2, whose names differ only in case")
    }

    @Test
    fun `an access policy takes the references of its own state, and the built-ins of every state`() {
        fun policies(extra: String) = SourceFile.kotlin(
            "Policies.kt",
            """
            package sample

            import me.ahoo.wow.api.access.AccessAttribute
            import me.ahoo.wow.api.access.accessPolicy

            class NoteState

            val documentAccess = accessPolicy<DocumentState> {
                readWrite { caller -> DocumentStateAccess.DEPARTMENT_NAME eq caller.claim("department") }
            }
            val noteAccess = accessPolicy<NoteState> { readWrite { caller -> AccessAttribute.OWNER eq caller.subject } }
            $extra
            """.trimIndent(),
        )
        val state = document("@Access val departmentName: String get() = department")
        compiled(state, policies(""))
        val wrong = "val wrong = accessPolicy<NoteState> { readWrite { DocumentStateAccess.DEPARTMENT_NAME eq \"x\" } }"
        val (_, result) = compileModel("generate", state, policies(wrong))
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains("Policies.kt")
    }

    @Test
    fun `the Manifest's built-in names are the programming model's`() {
        AccessAttributeName.BUILT_IN.assert().isEqualTo(AccessAttribute.BUILT_IN)
    }

    @Test
    fun `Access on the aggregate root rather than its state is a compile error`() {
        failing("", root = "@Access val rootDepartment: String get() = \"\"").assert().contains(
            "aggregate root Document's field rootDepartment is annotated @Access, which only a member of an " +
                "aggregate's state takes",
        )
    }
}
