package me.ahoo.wow.schema.openapi

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.command.wait.SimpleWaitSignal
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.example.api.order.CreateOrder
import me.ahoo.wow.example.domain.order.OrderState
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

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
            customizer = OpenAPISchemaBuilder.InlineCustomizer("")
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
    fun buildIn() {
        val openAPISchemaBuilder = OpenAPISchemaBuilder()
        assertThat(openAPISchemaBuilder.inline, equalTo(false))
        openAPISchemaBuilder.generateSchema(SimpleWaitSignal::class.java)
        val componentsSchemas = openAPISchemaBuilder.build()
        componentsSchemas.assert().hasSize(6)
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
}
