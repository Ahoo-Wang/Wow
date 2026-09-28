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

package me.ahoo.wow.openapi

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.descriptor.FieldDescriptor
import me.ahoo.wow.api.query.descriptor.QueryModelDescriptor
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.Reference
import me.ahoo.wow.example.api.ExampleService
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.QueryBudget
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.InferredQuerySchemaSource
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageFacts
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.describe
import me.ahoo.wow.schema.query.JsonQueryModelSource
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

/** The example domain's query descriptors carry the facts its model declares. */
class ExampleQueryDescriptorTest {
    private val product = Reference(
        contextName = ExampleService.SERVICE_NAME,
        aggregateName = ExampleService.PRODUCT_AGGREGATE_NAME,
    )

    /** Grants every logical path an exact match, as a storage would, so each field is described. */
    private val storage = QueryStorageAdapter { logical ->
        Mono.just(
            QueryStorageFacts(
                logical.values.keys.associateWith { path ->
                    QueryValueBindings(
                        mapOf(QueryCapability.EXACT_MATCH to QueryFieldBindingTemplate(path, null)),
                        projectionPath = path,
                    )
                },
            ),
        )
    }

    private fun describe(aggregateName: String, model: QueryModel): QueryModelDescriptor =
        DefaultQueryModelSchemaProvider(
            QuerySchemaContext(MaterializedNamedAggregate(ExampleService.SERVICE_NAME, aggregateName), model),
            listOf(InferredQuerySchemaSource(JsonQueryModelSource())),
            storage,
        ).schema().block()!!.describe(QueryBudget.HTTP_DEFAULT, 100)

    private fun QueryModelDescriptor.field(path: String): FieldDescriptor = fields.single { it.path == path }

    @Test
    fun `a cart item's and an order item's product id refer to the product aggregate`() {
        describe(ExampleService.CART_AGGREGATE_NAME, QueryModel.SNAPSHOT).field("state.items.productId")
            .semantic.assert().isEqualTo(product)
        describe(ExampleService.ORDER_AGGREGATE_NAME, QueryModel.SNAPSHOT).field("state.items.productId")
            .semantic.assert().isEqualTo(product)
    }

    @Test
    fun `the cart's events refer to the product aggregate too`() {
        val variants = checkNotNull(
            describe(ExampleService.CART_AGGREGATE_NAME, QueryModel.EVENT_STREAM).variants,
        ).values.associateBy { it.value.substringAfterLast('.') }
        variants.getValue("CartItemRemoved").fields.single { it.path == "body.productIds" }.semantic.assert()
            .isEqualTo(product)
        variants.getValue("CartItemAdded").fields.single { it.path == "body.added.productId" }.semantic.assert()
            .isEqualTo(product)
    }

    @Test
    fun `the snapshot's times carry their roles`() {
        val descriptor = describe(ExampleService.CART_AGGREGATE_NAME, QueryModel.SNAPSHOT)
        descriptor.field("eventTime").role.assert().isEqualTo(FieldDescriptor.EVENT_TIME)
        descriptor.field("firstEventTime").role.assert().isEqualTo(FieldDescriptor.FIRST_EVENT_TIME)
    }
}
