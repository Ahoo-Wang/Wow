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
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import reactor.test.StepVerifier
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

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
        StepVerifier.create(WorkingDirectoryQuerySchemaSource(tempDir).load(ORDER_CONTEXT))
            .verifyComplete()
    }

    @Test
    fun `convention file should preserve unset explicit null and typed values`() {
        val declaration = ClasspathQuerySchemaSource(javaClass.classLoader)
            .load(TEST_CONTEXT)
            .single()
            .block()!!

        val createdAt = declaration.fields.getValue(LogicalField("state.createdAt"))
        createdAt.title.assert().isEqualTo(DeclarationValue.Set(null))
        createdAt.description.assert().isEqualTo(DeclarationValue.Set("Creation time"))
        createdAt.enumValues.assert().isInstanceOf(DeclarationValue.Set::class.java)
        createdAt.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.INTEGER)))
        createdAt.nullable.assert().isEqualTo(DeclarationValue.Set(false))
        createdAt.required.assert().isEqualTo(DeclarationValue.Set(true))
        createdAt.cardinality.assert().isEqualTo(DeclarationValue.Set(QueryCardinality.SINGLE))
        createdAt.semanticType.assert().isEqualTo(DeclarationValue.Set(Temporal.Epoch(TimeUnit.MILLISECONDS)))
        createdAt.dynamicChildren.assert().isEqualTo(DeclarationValue.Set(false))

        val note = declaration.fields.getValue(LogicalField("state.note"))
        note.title.assert().isEqualTo(DeclarationValue.Set("Note"))
        note.description.assert().isEqualTo(DeclarationValue.Set(null))
        note.enumValues.assert().isEqualTo(DeclarationValue.Set(null))
        note.semanticType.assert().isEqualTo(DeclarationValue.Set(null))
        note.valueTypes.assert().isEqualTo(DeclarationValue.Unset)
    }

    @Test
    fun `malformed convention file should be unavailable with its cause`() {
        writeWorkingFile("{not-json")

        StepVerifier.create(WorkingDirectoryQuerySchemaSource(tempDir).load(ORDER_CONTEXT))
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
            """{"fields":{"state.value":{"valueTypes":null}}}""",
            """{"fields":{"state.value":{"required":null}}}""",
            """{"fields":{"state.value":{"cardinality":null}}}""",
            """{"fields":{"state.value":{"dynamicChildren":null}}}""",
        ).forEach { json ->
            writeWorkingFile(json)
            StepVerifier.create(WorkingDirectoryQuerySchemaSource(tempDir).load(ORDER_CONTEXT))
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
            WorkingDirectoryQuerySchemaSource(tempDir).load(traversal).collectList().block()
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
            ).fields.getValue(LogicalField("state.name")).title.assert().isEqualTo("Same")
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
            declarations.map { it.title() }.assert().containsExactly(
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
            source.load(ORDER_CONTEXT).single().block()!!.title().assert().isEqualTo(DeclarationValue.Set("Before"))
            source.load(CART_CONTEXT).single().block()

            Files.writeString(orderFile, conventionJson("After"))

            source.load(ORDER_CONTEXT).single().block()!!.title().assert().isEqualTo(DeclarationValue.Set("Before"))
            source.refresh(ORDER_CONTEXT).single().block()!!.title().assert().isEqualTo(DeclarationValue.Set("After"))
            source.load(CART_CONTEXT).single().block()!!.title().assert().isEqualTo(DeclarationValue.Set("Cart"))
        }
    }

    private fun writeWorkingFile(json: String): Path {
        val file = tempDir.resolve(ORDER_CONTEXT.resourcePathForTest())
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

    private fun conventionJson(title: String) =
        """{"fields":{"state.name":{"title":"$title"}}}"""

    private fun QuerySchemaDeclaration.title(): DeclarationValue<String?> =
        fields.getValue(LogicalField("state.name")).title

    private fun QuerySchemaContext.resourcePathForTest() =
        "wow-query-schema/${namedAggregate.contextName}/${namedAggregate.aggregateName}/${model.value.lowercase()}.json"

    private fun title(value: String): QuerySchemaDeclaration =
        QuerySchemaDeclaration(
            mapOf(LogicalField("state.name") to QueryFieldDeclaration(title = DeclarationValue.Set(value))),
        )

    companion object {
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
