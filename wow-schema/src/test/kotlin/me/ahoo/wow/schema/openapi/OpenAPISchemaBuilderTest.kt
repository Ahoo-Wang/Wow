package me.ahoo.wow.schema.openapi

import com.fasterxml.classmate.TypeResolver
import com.github.victools.jsonschema.generator.Option
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.command.wait.SimpleWaitSignal
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.example.api.order.CreateOrder
import me.ahoo.wow.example.domain.order.OrderState
import me.ahoo.wow.models.tree.Leaf
import me.ahoo.wow.schema.JsonSchemaGeneratorTest.SchemaData
import me.ahoo.wow.schema.SchemaGeneratorBuilder
import org.junit.jupiter.api.Test
import org.springframework.http.codec.ServerSentEvent

class OpenAPISchemaBuilderTest {

    @Test
    fun build() {
        val openAPISchemaBuilder = OpenAPISchemaBuilder()
        openAPISchemaBuilder.inline.assert().isFalse()
        val stringSchema = openAPISchemaBuilder.generateSchema(String::class.java)
        stringSchema.types.assert().contains("string")
        val createOderSchema = openAPISchemaBuilder.generateSchema(CreateOrder::class.java)
        createOderSchema.`$ref`.assert().isNull()
        val addCartItemSchema = openAPISchemaBuilder.generateSchema(AddCartItem::class.java)
        addCartItemSchema.`$ref`.assert().isNull()
        val orderStateSnapshotSchema = openAPISchemaBuilder.generateSchema(
            MaterializedSnapshot::class.java,
            OrderState::class.java
        )
        orderStateSnapshotSchema.`$ref`.assert().isNull()
        val orderStateSnapshotPagedListSchema = openAPISchemaBuilder.generateSchema(
            PagedList::class.java,
            openAPISchemaBuilder.resolveType(
                MaterializedSnapshot::class.java,
                OrderState::class.java
            )
        )
        orderStateSnapshotPagedListSchema.`$ref`.assert().isNull()
        val componentsSchemas = openAPISchemaBuilder.build()
        createOderSchema.`$ref`.assert().isNotNull()
        addCartItemSchema.`$ref`.assert().isNotNull()
        componentsSchemas.assert().hasSize(9)
    }

    @Test
    fun buildInline() {
        val openAPISchemaBuilder = OpenAPISchemaBuilder(
            schemaGeneratorBuilder = SchemaGeneratorBuilder().customizer {
                it.with(Option.INLINE_ALL_SCHEMAS)
            }
        )
        openAPISchemaBuilder.inline.assert().isTrue()
        val createOderSchema = openAPISchemaBuilder.generateSchema(CreateOrder::class.java)
        createOderSchema.`$ref`.assert().isNull()
        val addCartItemSchema = openAPISchemaBuilder.generateSchema(AddCartItem::class.java)
        addCartItemSchema.`$ref`.assert().isNull()
        val orderStateSnapshotSchema = openAPISchemaBuilder.generateSchema(
            MaterializedSnapshot::class.java,
            OrderState::class.java
        )
        orderStateSnapshotSchema.`$ref`.assert().isNull()
        val orderStateSnapshotPagedListSchema = openAPISchemaBuilder.generateSchema(
            PagedList::class.java,
            openAPISchemaBuilder.resolveType(
                MaterializedSnapshot::class.java,
                OrderState::class.java
            )
        )
        orderStateSnapshotPagedListSchema.`$ref`.assert().isNull()
        val componentsSchemas = openAPISchemaBuilder.build()
        componentsSchemas.assert().isEmpty()
    }

    @Test
    fun serverSentEvent() {
        val openAPISchemaBuilder = OpenAPISchemaBuilder(
            schemaGeneratorBuilder = SchemaGeneratorBuilder().customizer {
                it.with(Option.INLINE_ALL_SCHEMAS)
            }
        )
        openAPISchemaBuilder.inline.assert().isTrue()
        val schema = openAPISchemaBuilder.generateSchema(ServerSentEvent::class.java)

        val componentsSchemas = openAPISchemaBuilder.build()
        componentsSchemas.assert().isEmpty()
    }

    @Test
    fun buildIn() {
        val openAPISchemaBuilder = OpenAPISchemaBuilder()
        openAPISchemaBuilder.inline.assert().isFalse()
        openAPISchemaBuilder.generateSchema(SimpleWaitSignal::class.java)
        val componentsSchemas = openAPISchemaBuilder.build()
        componentsSchemas.assert().hasSize(7)
    }

    @Test
    fun condition() {
        val definitionPath = "${'$'}defs"
        val openAPISchemaBuilder = OpenAPISchemaBuilder(definitionPath = definitionPath)
        openAPISchemaBuilder.generateSchema(Condition::class.java)
        val componentsSchemas = openAPISchemaBuilder.build()
        val conditionSchema = componentsSchemas["wow.api.query.Condition"]
        val childrenItem = conditionSchema?.properties[Condition::children.name]?.items
        childrenItem.assert().isNotNull()
        childrenItem?.`$ref`.assert().startsWith("#/$definitionPath")
    }

    @Test
    fun leafCategory() {
        val openAPISchemaBuilder = OpenAPISchemaBuilder()
        openAPISchemaBuilder.generateSchema(LeafCategory::class.java)
        val componentsSchemas = openAPISchemaBuilder.build()
        val schema = componentsSchemas["wow.schema.LeafCategory"]
        val childrenItem = schema?.properties[LeafCategory::children.name]?.items
        childrenItem.assert().isNotNull()
    }

    @Test
    fun arrayType() {
        val openAPISchemaBuilder = OpenAPISchemaBuilder()
        val arrayType = TypeResolver().arrayType(SchemaData::class.java)
        val arrayTypeSchema = openAPISchemaBuilder.generateSchema(arrayType)
        val componentsSchemas = openAPISchemaBuilder.build()
        val schema = componentsSchemas["wow.schema.JsonSchemaGeneratorTest.SchemaData"]
        arrayTypeSchema.types.assert().contains("array")
    }
}

data class LeafCategory(
    override val children: List<LeafCategory>,
    override val sortId: Int,
    override val code: String,
    override val name: String
) : Leaf<LeafCategory> {
    override fun withChildren(children: List<LeafCategory>): LeafCategory {
        return this.copy(children = children)
    }
}
