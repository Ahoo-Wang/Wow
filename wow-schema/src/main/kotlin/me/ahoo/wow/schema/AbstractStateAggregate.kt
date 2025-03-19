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

package me.ahoo.wow.schema

import com.fasterxml.classmate.ResolvedType
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import com.github.victools.jsonschema.generator.SchemaKeyword
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.serialization.state.StateAggregateRecords
import java.lang.reflect.ParameterizedType

abstract class AbstractStateAggregate<S : ReadOnlyStateAggregate<*>> :
    TypedCustomDefinitionProvider() {
    @Suppress("UNCHECKED_CAST")
    override val type: Class<*> by lazy {
        val superType = javaClass.genericSuperclass as ParameterizedType
        val messageType = superType.actualTypeArguments[0] as ParameterizedType
        messageType.rawType as Class<S>
    }

    open fun getStateType(javaType: ResolvedType): ResolvedType {
        return javaType.typeBindings.getBoundType(0)
    }

    override fun createCustomDefinition(javaType: ResolvedType, context: SchemaGenerationContext): CustomDefinition {
        if (javaType.typeBindings.isEmpty) {
            return super.createCustomDefinition(javaType, context)
        }

        val typedSchema = getTypedSchema()
        typedSchema.remove(SchemaKeyword.TAG_TITLE.toTagKey())
        val stateType = getStateType(javaType)
        val propertiesKey = SchemaKeyword.TAG_PROPERTIES.toTagKey()
        val propertiesNode = typedSchema[propertiesKey] as ObjectNode
        val stateOriginalNode = propertiesNode[StateAggregateRecords.STATE] as ObjectNode
        val stateNode = context.createStandardDefinition(stateType, this)
        val descriptionKey = SchemaKeyword.TAG_DESCRIPTION.toTagKey()
        stateNode.set<ObjectNode>(descriptionKey, stateOriginalNode[descriptionKey])
        propertiesNode.set<ObjectNode>(StateAggregateRecords.STATE, stateNode)
        return CustomDefinition(typedSchema)
    }
}

object StateAggregateDefinitionProvider : AbstractStateAggregate<StateAggregate<*>>()
object SnapshotDefinitionProvider : AbstractStateAggregate<Snapshot<*>>()
object StateEventDefinitionProvider : AbstractStateAggregate<StateEvent<*>>()
