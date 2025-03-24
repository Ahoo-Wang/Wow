package me.ahoo.wow.schema.openapi

import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.example.api.order.CreateOrder
import me.ahoo.wow.example.domain.order.OrderState
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.Test

class OpenAPISchemaBuilderTest {

    @Test
    fun build() {
        val openAPISchemaBuilder = OpenAPISchemaBuilder()
        val createOderSchema = openAPISchemaBuilder.generateSchema(CreateOrder::class.java)
        assertThat(createOderSchema.`$ref`, nullValue())
        val addCartItemSchema = openAPISchemaBuilder.generateSchema(AddCartItem::class.java)
        assertThat(addCartItemSchema.`$ref`, nullValue())
        val orderStateSnapshotSchema = openAPISchemaBuilder.generateSchema(
            MaterializedSnapshot::class.java,
            OrderState::class.java
        )
        assertThat(orderStateSnapshotSchema.`$ref`, nullValue())
        val orderStateSnapshotPagedListSchema = openAPISchemaBuilder.generateSchema(
            PagedList::class.java,
            openAPISchemaBuilder.resolveType(
                MaterializedSnapshot::class.java,
                OrderState::class.java
            )
        )
        assertThat(orderStateSnapshotPagedListSchema.`$ref`, nullValue())
        val componentsSchemas = openAPISchemaBuilder.build()
        assertThat(createOderSchema.`$ref`, notNullValue())
        assertThat(addCartItemSchema.`$ref`, notNullValue())
        assertThat(componentsSchemas.size, equalTo(9))
    }
}
