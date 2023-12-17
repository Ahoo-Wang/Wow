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
package me.ahoo.wow.modeling.annotation

import me.ahoo.wow.annotation.AggregateAnnotationParser.toAggregateIdGetterIfAnnotated
import me.ahoo.wow.annotation.AggregateAnnotationParser.toStringGetter
import me.ahoo.wow.api.annotation.DEFAULT_AGGREGATE_ID_NAME
import me.ahoo.wow.api.annotation.DEFAULT_ON_SOURCING_NAME
import me.ahoo.wow.api.annotation.OnSourcing
import me.ahoo.wow.infra.accessor.constructor.DefaultConstructorAccessor
import me.ahoo.wow.infra.accessor.property.PropertyGetter
import me.ahoo.wow.infra.reflection.ClassMetadata
import me.ahoo.wow.infra.reflection.ClassVisitor
import me.ahoo.wow.messaging.function.FunctionMetadataParser.toFunctionMetadata
import me.ahoo.wow.messaging.function.MethodFunctionMetadata
import me.ahoo.wow.metadata.CacheableMetadataParser
import me.ahoo.wow.modeling.matedata.StateAggregateMetadata
import org.slf4j.LoggerFactory
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

private val log = LoggerFactory.getLogger(StateAggregateMetadataParser::class.java)

/**
 * State Aggregate Metadata Parser .
 *
 * @author ahoo wang
 */
object StateAggregateMetadataParser : CacheableMetadataParser<Class<*>, StateAggregateMetadata<*>>() {

    override fun parseToMetadata(type: Class<*>): StateAggregateMetadata<*> {
        val visitor = StateAggregateMetadataVisitor(type)
        ClassMetadata.visit(type, visitor)
        return visitor.toMetadata()
    }
}

internal class StateAggregateMetadataVisitor<S : Any>(private val stateAggregateType: Class<S>) :
    ClassVisitor {
    private val constructor: Constructor<S>
    private var aggregateIdGetter: PropertyGetter<S, String>? = null
    private val sourcingFunctionRegistry: MutableMap<Class<*>, MethodFunctionMetadata<S, Void>> = HashMap()
    private var namedIdField: Field? = null

    init {
        try {
            @Suppress("UNCHECKED_CAST")
            constructor = stateAggregateType.declaredConstructors.first {
                (it.parameterCount == 1 || it.parameterCount == 2) &&
                    it.parameterTypes.all { parameterType -> parameterType == String::class.java }
            } as Constructor<S>
        } catch (e: NoSuchElementException) {
            throw IllegalStateException(
                "Failed to parse StateAggregate[$stateAggregateType] metadata: Not defined Constructor[ctor(id) or ctor(id,tenantId)].",
            )
        }
    }

    override fun visitField(field: Field) {
        if (aggregateIdGetter == null) {
            aggregateIdGetter = field.toAggregateIdGetterIfAnnotated()
        }
        if (namedIdField == null && DEFAULT_AGGREGATE_ID_NAME == field.name) {
            namedIdField = field
        }
    }

    override fun visitMethod(method: Method) {
        if (aggregateIdGetter == null) {
            aggregateIdGetter = method.toAggregateIdGetterIfAnnotated()
        }
        if (method.isAnnotationPresent(OnSourcing::class.java) ||
            (DEFAULT_ON_SOURCING_NAME == method.name && method.parameterCount == 1)
        ) {
            val functionMetadata = method.toFunctionMetadata<S, Void>()
            sourcingFunctionRegistry.putIfAbsent(functionMetadata.supportedType, functionMetadata)
        }
    }

    override fun end() {
        if (aggregateIdGetter != null || namedIdField == null) {
            return
        }

        aggregateIdGetter = namedIdField!!.toStringGetter()
    }

    fun toMetadata(): StateAggregateMetadata<S> {
        if (sourcingFunctionRegistry.isEmpty()) {
            if (log.isWarnEnabled) {
                log.warn("StateAggregate[$stateAggregateType] requires at least one OnSourcing function!")
            }
        }

        return StateAggregateMetadata(
            aggregateType = stateAggregateType,
            constructorAccessor = DefaultConstructorAccessor(constructor),
            aggregateIdAccessor = requireNotNull(aggregateIdGetter),
            sourcingFunctionRegistry = sourcingFunctionRegistry,
        )
    }
}

fun <S : Any> Class<out S>.stateAggregateMetadata(): StateAggregateMetadata<S> {
    @Suppress("UNCHECKED_CAST")
    return StateAggregateMetadataParser.parse(this) as StateAggregateMetadata<S>
}

inline fun <reified S : Any> stateAggregateMetadata(): StateAggregateMetadata<S> {
    return S::class.java.stateAggregateMetadata()
}
