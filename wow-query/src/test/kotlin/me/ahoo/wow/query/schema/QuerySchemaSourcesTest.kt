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

package me.ahoo.wow.query.schema

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import reactor.test.StepVerifier
import java.io.ByteArrayInputStream
import java.net.URL
import java.net.URLClassLoader
import java.net.URLConnection
import java.net.URLStreamHandler
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class QuerySchemaSourcesTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `bean source should not leak registrations across contexts`() {
        val orderDeclaration = title("Order")
        val cartDeclaration = title("Cart")
        val otherModelDeclaration = title("Other model")
        val source = BeanQuerySchemaSource(
            listOf(
                QuerySchemaRegistration(ORDER_CONTEXT, orderDeclaration),
                QuerySchemaRegistration(CART_CONTEXT, cartDeclaration),
                QuerySchemaRegistration(ORDER_CONTEXT.copy(model = QueryModel("CUSTOM")), otherModelDeclaration),
            ),
        )

        source.load(ORDER_CONTEXT).collectList().block()!!
            .assert().containsExactly(orderDeclaration)
    }

    @Test
    fun `missing working directory file should be empty`() {
        StepVerifier.create(WorkingDirectoryQuerySchemaSource(basePath = tempDir).load(ORDER_CONTEXT))
            .verifyComplete()
    }

    @Test
    fun `working directory source should read the convention path only`() {
        writeLegacyFile(tempDir, conventionJson("Legacy"))
        StepVerifier.create(WorkingDirectoryQuerySchemaSource(basePath = tempDir).load(ORDER_CONTEXT)).verifyComplete()

        writeWorkingFile(conventionJson("Unified"))
        WorkingDirectoryQuerySchemaSource(basePath = tempDir).load(ORDER_CONTEXT)
            .single().block()!!.text().assert().isEqualTo(DeclarationValue.Set("Unified"))
    }

    @Test
    fun `classpath source should read the convention path only`() {
        val root = tempDir.resolve("root")
        writeLegacyFile(root, conventionJson("Legacy"))
        URLClassLoader(arrayOf(root.toUri().toURL()), null).use { loader ->
            ClasspathQuerySchemaSource(loader).load(ORDER_CONTEXT).collectList().block()!!.assert().isEmpty()
        }

        writeClasspathFile(root, conventionJson("Unified"))
        URLClassLoader(arrayOf(root.toUri().toURL()), null).use { loader ->
            ClasspathQuerySchemaSource(loader).load(ORDER_CONTEXT)
                .single().block()!!.text().assert().isEqualTo(DeclarationValue.Set("Unified"))
        }
    }

    @Test
    fun `unified classpath resources should preserve same-priority merge behavior`() {
        val firstRoot = tempDir.resolve("a")
        val secondRoot = tempDir.resolve("z")
        writeClasspathFile(firstRoot, conventionJson("Same"))
        writeClasspathFile(secondRoot, conventionJson("Same"))

        URLClassLoader(arrayOf(secondRoot.toUri().toURL(), firstRoot.toUri().toURL()), null).use { loader ->
            ClasspathQuerySchemaSource(loader).load(ORDER_CONTEXT).collectList().block()!!
                .assert().hasSize(2)
        }
    }

    @Test
    fun `declaration file supplies types, enum with descriptions, time encoding and map values`() {
        val declaration = ClasspathQuerySchemaSource(javaClass.classLoader)
            .load(TEST_CONTEXT)
            .single()
            .block()!!

        val createdAt = declaration.fields.getValue(QueryField("state.createdAt"))
        createdAt.kind.assert().isEqualTo(DeclarationValue.Set(QueryValueKind.SCALAR))
        createdAt.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.INTEGER)))
        createdAt.nullable.assert().isEqualTo(DeclarationValue.Set(false))
        createdAt.description.assert().isEqualTo(DeclarationValue.Set("Creation time"))
        createdAt.semanticType.assert().isEqualTo(DeclarationValue.Set(Temporal.Epoch(TimeUnit.MILLISECONDS)))
        createdAt.enumValues.assert().isEqualTo(DeclarationValue.Unset)
        createdAt.title.assert().isEqualTo(DeclarationValue.Unset)
        createdAt.required.assert().isEqualTo(DeclarationValue.Unset)

        val status = declaration.fields.getValue(QueryField("state.status"))
        status.enumValues.valueOr(null)!!.map { it.stringValue() }.assert().containsExactly("PAID", "SHIPPED")
        status.enumDescriptions.valueOr(emptyMap()).mapKeys { it.key.stringValue() }.assert()
            .isEqualTo(mapOf("PAID" to "Paid"))

        val attributes = declaration.fields.getValue(QueryField("state.attributes"))
        attributes.additionalProperties.valueOr(null)!!.items.valueOr(null)!!.nullable.assert()
            .isEqualTo(DeclarationValue.Set(false))
        attributes.properties.valueOr(emptyMap()).getValue("owner").valueTypes.assert()
            .isEqualTo(DeclarationValue.Set(setOf(QueryValueType.STRING)))
    }

    @Test
    fun `malformed convention file should be unavailable with its cause`() {
        writeWorkingFile("{not-json")

        StepVerifier.create(WorkingDirectoryQuerySchemaSource(basePath = tempDir).load(ORDER_CONTEXT))
            .expectErrorSatisfies { error ->
                error.assert().isInstanceOf(QuerySchemaUnavailableException::class.java)
                error.cause.assert().isNotNull()
            }
            .verify()
    }

    @Test
    fun `invalid convention shapes should be unavailable`() {
        listOf(
            "[]",
            """{"unknown":{}}""",
            """{"fields":[]}""",
            """{"fields":{"state.value":[]}}""",
            """{"fields":{"state.value":{"unknown":true}}}""",
            """{"fields":{"state.value":{"nullable":null}}}""",
            """{"fields":{"state.value":{"types":[]}}}""",
            """{"fields":{"state.value":{"types":["OBJECT"]}}}""",
            """{"fields":{"state.value":{"kind":"UNION"}}}""",
            """{"fields":{"state.value":{"enum":["A"]}}}""",
            """{"fields":{"state.value":{"enum":[{"value":"A"},{"value":"A"}]}}}""",
            """{"fields":{"state.value":{"enum":[{"value":"A","label":"a"}]}}}""",
            """{"fields":{"state.value":{"semantic":null}}}""",
            // The retired format's keys are unknown now.
            """{"fields":{"state.value":{"title":"Value"}}}""",
            """{"fields":{"state.value":{"valueTypes":["STRING"]}}}""",
            """{"fields":{"state.value":{"enumValues":["A"]}}}""",
            """{"fields":{"state.value":{"required":true}}}""",
            """{"fields":{"state.value":{"additionalProperties":{}}}}""",
            """{"fields":{"state.value":{"alternatives":[]}}}""",
            """{"fields":{"state.value":{"semanticType":{"type":"TEMPORAL_DATE"}}}}""",
        ).forEach { json ->
            writeWorkingFile(json)
            StepVerifier.create(WorkingDirectoryQuerySchemaSource(basePath = tempDir).load(ORDER_CONTEXT))
                .expectError(QuerySchemaUnavailableException::class.java)
                .verify()
        }
    }

    @Test
    fun `convention path should reject traversal segments`() {
        val traversal = QuerySchemaContext(
            MaterializedNamedAggregate("..", "test-aggregate"),
            QueryModel.SNAPSHOT,
        )

        assertThrows<IllegalArgumentException> {
            WorkingDirectoryQuerySchemaSource(basePath = tempDir).load(traversal).collectList().block()
        }
    }

    @Test
    fun `classpath resources should be sorted and equal declarations should merge`() {
        val firstRoot = tempDir.resolve("a")
        val secondRoot = tempDir.resolve("z")
        writeClasspathFile(firstRoot, conventionJson("Same"))
        writeClasspathFile(secondRoot, conventionJson("Same"))

        URLClassLoader(arrayOf(secondRoot.toUri().toURL(), firstRoot.toUri().toURL()), null).use { classLoader ->
            val source = ClasspathQuerySchemaSource(classLoader)
            val declarations = source.load(ORDER_CONTEXT).collectList().block()!!
            declarations.assert().hasSize(2)

            QuerySchemaMerger().merge(
                SystemQuerySchemaSource.declaration(QueryModel.SNAPSHOT),
                declarations.map { PrioritizedQuerySchemaDeclaration(source.priority, it) },
            ).value(QueryField("state.name").toPathTemplate())!!.description.assert().isEqualTo("Same")
        }
    }

    @Test
    fun `conflicting same priority classpath resources should be rejected by merger`() {
        val firstRoot = tempDir.resolve("a")
        val secondRoot = tempDir.resolve("z")
        writeClasspathFile(firstRoot, conventionJson("First"))
        writeClasspathFile(secondRoot, conventionJson("Second"))

        URLClassLoader(arrayOf(secondRoot.toUri().toURL(), firstRoot.toUri().toURL()), null).use { classLoader ->
            val source = ClasspathQuerySchemaSource(classLoader)
            val declarations = source.load(ORDER_CONTEXT).collectList().block()!!
            declarations.map { it.text() }.assert().containsExactly(
                DeclarationValue.Set("First"),
                DeclarationValue.Set("Second"),
            )

            assertThrows<QuerySchemaConflictException> {
                QuerySchemaMerger().merge(
                    SystemQuerySchemaSource.declaration(QueryModel.SNAPSHOT),
                    declarations.map { PrioritizedQuerySchemaDeclaration(source.priority, it) },
                )
            }
        }
    }

    @Test
    fun `classpath refresh should evict only the requested context and reread content`() {
        val root = tempDir.resolve("root")
        val orderFile = writeClasspathFile(root, conventionJson("Before"), ORDER_CONTEXT)
        writeClasspathFile(root, conventionJson("Cart"), CART_CONTEXT)

        URLClassLoader(arrayOf(root.toUri().toURL()), null).use { classLoader ->
            val source = ClasspathQuerySchemaSource(classLoader)
            source.load(ORDER_CONTEXT).single().block()!!.text().assert().isEqualTo(DeclarationValue.Set("Before"))
            source.load(CART_CONTEXT).single().block()

            Files.writeString(orderFile, conventionJson("After"))

            source.load(ORDER_CONTEXT).single().block()!!.text().assert().isEqualTo(DeclarationValue.Set("Before"))
            source.refresh(ORDER_CONTEXT).single().block()!!.text().assert().isEqualTo(DeclarationValue.Set("After"))
            source.load(CART_CONTEXT).single().block()!!.text().assert().isEqualTo(DeclarationValue.Set("Cart"))
        }
    }

    @Test
    fun `classpath source should enumerate and read resources off the caller thread`() {
        val ioThreads = mutableListOf<String>()
        val resource = URL(
            null,
            "memory:snapshot.json",
            object : URLStreamHandler() {
                override fun openConnection(url: URL): URLConnection = object : URLConnection(url) {
                    override fun connect() = Unit

                    override fun getInputStream() = ByteArrayInputStream(
                        conventionJson("Async").also { ioThreads += Thread.currentThread().name }.toByteArray(),
                    )
                }
            },
        )
        val classLoader = object : ClassLoader(null) {
            override fun getResources(name: String): java.util.Enumeration<URL> {
                ioThreads += Thread.currentThread().name
                return Collections.enumeration(listOf(resource))
            }
        }

        onNamedCallerThread {
            ClasspathQuerySchemaSource(classLoader).load(ORDER_CONTEXT).single().block()
        }

        ioThreads.assert().hasSize(2).doesNotContain(CALLER_THREAD)
    }

    @Test
    fun `working directory source should read file off the caller thread`() {
        writeWorkingFile(conventionJson("Async"))
        val readThread = AtomicReference<String>()
        val source = WorkingDirectoryQuerySchemaSource(
            basePath = tempDir,
            readText = { file ->
                readThread.set(Thread.currentThread().name)
                Files.readString(file)
            },
        )

        onNamedCallerThread {
            source.load(ORDER_CONTEXT).single().block()
        }

        readThread.get().assert().isNotEqualTo(CALLER_THREAD)
    }

    @Test
    fun `Kotlin DSL expresses named array and dynamic value structure`() {
        val declaration = QuerySchemaDeclarationBuilder().apply {
            field("state.addresses") {
                property("home") {
                    items {
                        types(QueryValueType.STRING)
                        nullable(false)
                    }
                }
                values { items { types(QueryValueType.STRING) } }
            }
            field("state.status") {
                types(QueryValueType.STRING)
                enumValue("PAID", "Paid")
                enumValue("SHIPPED")
            }
            field("state.placedOn") {
                types(QueryValueType.STRING)
                temporalFormatted("yyyy-MM-dd")
            }
        }.build()
        val value = declaration.fields.getValue(QueryField("state.addresses"))
        value.properties.valueOr(
            emptyMap()
        ).getValue("home").items.valueOr(null)!!.nullable.assert().isEqualTo(DeclarationValue.Set(false))
        value.additionalProperties.valueOr(
            null
        )!!.items.valueOr(null)!!.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.STRING)))
        val status = declaration.fields.getValue(QueryField("state.status"))
        status.enumValues.valueOr(null)!!.map { it.stringValue() }.assert().containsExactly("PAID", "SHIPPED")
        status.enumDescriptions.valueOr(emptyMap()).values.assert().containsExactly("Paid")
        declaration.fields.getValue(QueryField("state.placedOn")).semanticType.assert()
            .isEqualTo(DeclarationValue.Set(Temporal.Formatted("yyyy-MM-dd")))
    }

    private fun writeWorkingFile(json: String): Path {
        val file = tempDir.resolve(ORDER_CONTEXT.workingPathForTest())
        Files.createDirectories(file.parent)
        return Files.writeString(file, json)
    }

    private fun writeClasspathFile(
        root: Path,
        json: String,
        context: QuerySchemaContext = ORDER_CONTEXT,
    ): Path {
        val file = root.resolve(context.resourcePathForTest())
        Files.createDirectories(file.parent)
        return Files.writeString(file, json)
    }

    /** The retired `wow-query-schema/{context}/{aggregate}/{model}.json` location, which nothing reads any more. */
    private fun writeLegacyFile(root: Path, json: String): Path {
        val file = root.resolve(
            "wow-query-schema/${ORDER_CONTEXT.namedAggregate.contextName}/" +
                "${ORDER_CONTEXT.namedAggregate.aggregateName}/snapshot.json"
        )
        Files.createDirectories(file.parent)
        return Files.writeString(file, json)
    }

    private fun conventionJson(description: String) =
        """{"fields":{"state.name":{"description":"$description"}}}"""

    private fun QuerySchemaDeclaration.text(): DeclarationValue<String?> =
        fields.getValue(QueryField("state.name")).description

    private fun QuerySchemaContext.resourcePathForTest() =
        "META-INF/wow/query-schema/" +
            "${namedAggregate.contextName}.${namedAggregate.aggregateName}.${model.value.lowercase()}.json"

    private fun QuerySchemaContext.workingPathForTest() =
        "wow/query-schema/" +
            "${namedAggregate.contextName}.${namedAggregate.aggregateName}.${model.value.lowercase()}.json"

    private fun title(value: String): QuerySchemaDeclaration =
        QuerySchemaDeclaration(
            mapOf(QueryField("state.name") to QueryFieldDeclaration(title = DeclarationValue.Set(value))),
        )

    private fun <T> onNamedCallerThread(block: () -> T): T {
        val thread = Thread.currentThread()
        val originalName = thread.name
        thread.name = CALLER_THREAD
        return try {
            block()
        } finally {
            thread.name = originalName
        }
    }

    companion object {
        private const val CALLER_THREAD = "query-schema-caller"
        private val ORDER_CONTEXT = QuerySchemaContext(
            MaterializedNamedAggregate("test-context", "order"),
            QueryModel.SNAPSHOT,
        )
        private val CART_CONTEXT = QuerySchemaContext(
            MaterializedNamedAggregate("test-context", "cart"),
            QueryModel.SNAPSHOT,
        )
        private val TEST_CONTEXT = QuerySchemaContext(
            MaterializedNamedAggregate("test-context", "test-aggregate"),
            QueryModel.SNAPSHOT,
        )
    }
}
