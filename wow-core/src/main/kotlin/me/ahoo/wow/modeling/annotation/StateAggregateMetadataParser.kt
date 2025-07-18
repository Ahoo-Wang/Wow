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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.toAggregateIdGetterIfAnnotated
import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.toStringGetter
import me.ahoo.wow.api.annotation.DEFAULT_AGGREGATE_ID_NAME
import me.ahoo.wow.api.annotation.DEFAULT_ON_SOURCING_NAME
import me.ahoo.wow.api.annotation.OnSourcing
import me.ahoo.wow.infra.accessor.constructor.DefaultConstructorAccessor
import me.ahoo.wow.infra.accessor.property.PropertyGetter
import me.ahoo.wow.infra.reflection.ClassMetadata.visit
import me.ahoo.wow.infra.reflection.ClassVisitor
import me.ahoo.wow.messaging.function.FunctionAccessorMetadata
import me.ahoo.wow.messaging.function.FunctionMetadataParser.toFunctionMetadata
import me.ahoo.wow.metadata.CacheableMetadataParser
import me.ahoo.wow.metadata.Metadata
import me.ahoo.wow.modeling.matedata.StateAggregateMetadata
import java.lang.reflect.Constructor
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty1
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.javaConstructor
import kotlin.reflect.jvm.javaType

/**
 * State Aggregate Metadata Parser .
 *
 * @author ahoo wang
 */
object StateAggregateMetadataParser : CacheableMetadataParser() {

    override fun <TYPE : Any, M : Metadata> parseToMetadata(type: Class<TYPE>): M {
        val visitor = StateAggregateMetadataVisitor(type)
        type.kotlin.visit(visitor)
        @Suppress("UNCHECKED_CAST")
        return visitor.toMetadata() as M
    }
}

internal class StateAggregateMetadataVisitor<S : Any>(private val stateAggregateType: Class<S>) :
    ClassVisitor<S, StateAggregateMetadata<S>> {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val constructor: Constructor<S>
    private var aggregateIdGetter: PropertyGetter<S, String>? = null
    private val sourcingFunctionRegistry: MutableMap<Class<*>, FunctionAccessorMetadata<S, Void>> = HashMap()
    private var namedIdProperty: KProperty1<S, String>? = null

    init {
        try {
            constructor = stateAggregateType.kotlin.constructors.first {
                (it.parameters.count() <= 2) &&
                    it.parameters.all { parameter ->
                        parameter.type.javaType == String::class.java
                    }
            }.javaConstructor as Constructor<S>
        } catch (_: NoSuchElementException) {
            throw IllegalStateException(
                "Failed to parse StateAggregate[${stateAggregateType.name}] metadata: Missing valid constructor. Expected one of [ctor(), ctor(id), or ctor(id, tenantId)] with String parameters only.",
            )
        }
    }

    override fun visitProperty(property: KProperty1<S, *>) {
        if (aggregateIdGetter == null) {
            aggregateIdGetter = property.toAggregateIdGetterIfAnnotated()
        }
        if (namedIdProperty == null &&
            DEFAULT_AGGREGATE_ID_NAME == property.name &&
            property.returnType.javaType == String::class.java
        ) {
            @Suppress("UNCHECKED_CAST")
            namedIdProperty = property as KProperty1<S, String>
        }
    }

    override fun visitFunction(function: KFunction<*>) {
        if (function.hasAnnotation<OnSourcing>() ||
            (DEFAULT_ON_SOURCING_NAME == function.name && function.valueParameters.count() == 1)
        ) {
            val functionMetadata = function.toFunctionMetadata<S, Void>()
            sourcingFunctionRegistry.putIfAbsent(functionMetadata.supportedType, functionMetadata)
        }
    }

    override fun end() {
        if (aggregateIdGetter != null || namedIdProperty == null) {
            return
        }

        aggregateIdGetter = namedIdProperty!!.toStringGetter()
    }

    override fun toMetadata(): StateAggregateMetadata<S> {
        if (sourcingFunctionRegistry.isEmpty()) {
            log.warn {
                "StateAggregate[$stateAggregateType] requires at least one OnSourcing function!"
            }
        }

        return StateAggregateMetadata(
            aggregateType = stateAggregateType,
            constructorAccessor = DefaultConstructorAccessor(constructor),
            aggregateIdAccessor = aggregateIdGetter,
            sourcingFunctionRegistry = sourcingFunctionRegistry,
        )
    }
}

fun <S : Any> Class<out S>.stateAggregateMetadata(): StateAggregateMetadata<S> {
    return StateAggregateMetadataParser.parse(this)
}

inline fun <reified S : Any> stateAggregateMetadata(): StateAggregateMetadata<S> {
    return S::class.java.stateAggregateMetadata()
}
