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

package me.ahoo.wow.schema.definition

import com.fasterxml.classmate.ResolvedType
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import com.github.victools.jsonschema.generator.SchemaKeyword
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import tools.jackson.databind.node.ObjectNode

/**
 * A framework wrapper whose bundled schema holds the wrapped value in one [slot]: a message's `body` or a state
 * aggregate's `state`. For `Wrapper<T>` the slot gets the definition of `T` and keeps the bundled description; a raw or
 * `Any` wrapper keeps the bundled schema as it is.
 */
internal class WrappedDefinitionProvider private constructor(
    type: Class<*>,
    private val slot: String,
    /** The property that names the wrapped type, written as a `const` of `T`'s class name. */
    private val typeNameProperty: String?,
    private val keepTitle: Boolean,
) : BundledDefinitionProvider(type) {
    companion object {
        /** A message: `body` holds `T` and `bodyType` its class name. */
        fun message(type: Class<*>): WrappedDefinitionProvider =
            WrappedDefinitionProvider(type, MessageRecords.BODY, MessageRecords.BODY_TYPE, keepTitle = true)

        /** A state aggregate, snapshot or state event: `state` holds `T`, and the wrapper title gives way to `T`'s. */
        fun state(type: Class<*>): WrappedDefinitionProvider =
            WrappedDefinitionProvider(type, StateAggregateRecords.STATE, typeNameProperty = null, keepTitle = false)
    }

    override fun createCustomDefinition(javaType: ResolvedType, context: SchemaGenerationContext): CustomDefinition {
        val wrappedType = javaType.typeBindings.getBoundType(0)
        if (wrappedType == null || wrappedType.erasedType == Any::class.java) {
            return super.createCustomDefinition(javaType, context)
        }
        val schema = WowSchemaLoader.load(type)
        if (!keepTitle) {
            schema.remove(context.getKeyword(SchemaKeyword.TAG_TITLE))
        }
        val properties = schema[context.getKeyword(SchemaKeyword.TAG_PROPERTIES)] as ObjectNode
        typeNameProperty?.let { name ->
            val typeNameNode = properties[name] as ObjectNode
            typeNameNode.remove(context.getKeyword(SchemaKeyword.TAG_TYPE))
            typeNameNode.put(context.getKeyword(SchemaKeyword.TAG_CONST), wrappedType.erasedType.name)
        }
        val descriptionKey = context.getKeyword(SchemaKeyword.TAG_DESCRIPTION)
        val wrappedSchema = context.createStandardDefinition(wrappedType, this)
        wrappedSchema.set(descriptionKey, properties[slot][descriptionKey])
        properties.set(slot, wrappedSchema)
        return CustomDefinition(schema)
    }
}
