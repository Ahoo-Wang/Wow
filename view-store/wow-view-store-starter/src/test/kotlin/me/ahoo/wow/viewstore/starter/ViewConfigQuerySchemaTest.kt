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

package me.ahoo.wow.viewstore.starter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.InferredQuerySchemaSource
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageFacts
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.schema.query.JsonQueryModelSource
import me.ahoo.wow.viewstore.domain.view.ViewConfigs
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

@OptIn(InternalWowApi::class, WowSpi::class)
class ViewConfigQuerySchemaTest {
    /**
     * The registration before 9.3.0: built with the DSL, then the value types of `state.config` set to `OBJECT` by
     * hand on the built declaration, since a declaration could not open an opaque value.
     */
    private fun handEditedRegistration(): QuerySchemaRegistration {
        val registration = ViewConfigQuerySchema.registration()
        val fields = registration.declaration.fields.mapValues { (field, declaration) ->
            if (field.path == ViewConfigQuerySchema.CONFIG_FIELD) {
                declaration.copy(valueTypes = DeclarationValue.Set(setOf(QueryValueType.OBJECT)))
            } else {
                declaration
            }
        }
        return registration.copy(declaration = registration.declaration.copy(fields = fields))
    }

    /** The logical schema of the view's snapshot model, inferred and then declared by [registration]. */
    private fun logicalSchema(registration: QuerySchemaRegistration): LogicalQuerySchema {
        var compiled: LogicalQuerySchema? = null
        val adapter = object : QueryStorageAdapter {
            override fun facts(logicalSchema: LogicalQuerySchema): Mono<QueryStorageFacts> {
                compiled = logicalSchema
                return Mono.just(QueryStorageFacts(emptyMap()))
            }
        }
        DefaultQueryModelSchemaProvider(
            registration.context,
            listOf(InferredQuerySchemaSource(JsonQueryModelSource()), BeanQuerySchemaSource(listOf(registration))),
            adapter,
        ).schema().onErrorResume { Mono.empty() }.block()
        return checkNotNull(compiled) { "The view's snapshot schema was not compiled." }
    }

    private fun QueryValueSchema.describe(): String = buildString {
        append(kind).append(valueTypes).append(" nullable=").append(nullable).append(" required=").append(required)
        append(" semantic=").append(semanticType).append(" mask=").append(maskRule)
        properties.toSortedMap().forEach { (name, value) ->
            append(
                " "
            ).append(name).append("{").append(value.describe()).append("}")
        }
        items?.let { append(" items{").append(it.describe()).append("}") }
        additionalProperties?.let { append(" values{").append(it.describe()).append("}") }
        alternatives.forEach { append(" |").append(it.describe()) }
    }

    @Test
    fun `the config declaration compiles to the schema the hand-edited one did`() {
        val registration = ViewConfigQuerySchema.registration()
        registration.context.model.assert().isEqualTo(QueryModel.SNAPSHOT)
        val declared = logicalSchema(registration)
        val handEdited = logicalSchema(handEditedRegistration())
        declared.root.describe().assert().isEqualTo(handEdited.root.describe())
        declared.values.keys.assert().isEqualTo(handEdited.values.keys)

        val config = declared.root.properties.getValue("state").properties.getValue("config")
        config.kind.assert().isEqualTo(QueryValueKind.OBJECT)
        config.valueTypes.assert().isEqualTo(setOf(QueryValueType.OBJECT))
        config.properties.getValue(ViewConfigs.KIND).valueTypes.assert().isEqualTo(setOf(QueryValueType.STRING))
        config.properties.getValue(ViewConfigs.PANELS).kind.assert().isEqualTo(QueryValueKind.ARRAY)
    }
}
