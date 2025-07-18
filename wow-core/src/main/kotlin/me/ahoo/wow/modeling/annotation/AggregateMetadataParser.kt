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
import me.ahoo.wow.annotation.sortedByOrder
import me.ahoo.wow.api.annotation.AfterCommand
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.DEFAULT_AFTER_COMMAND_NAME
import me.ahoo.wow.api.annotation.DEFAULT_ON_COMMAND_NAME
import me.ahoo.wow.api.annotation.DEFAULT_ON_ERROR_NAME
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.annotation.OnError
import me.ahoo.wow.api.annotation.StaticTenantId
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.configuration.WOW_METADATA_RESOURCE_NAME
import me.ahoo.wow.infra.accessor.constructor.DefaultConstructorAccessor
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import me.ahoo.wow.infra.reflection.ClassMetadata.visit
import me.ahoo.wow.infra.reflection.ClassVisitor
import me.ahoo.wow.messaging.function.FunctionAccessorMetadata
import me.ahoo.wow.messaging.function.FunctionMetadataParser.toMonoFunctionMetadata
import me.ahoo.wow.metadata.CacheableMetadataParser
import me.ahoo.wow.metadata.Metadata
import me.ahoo.wow.modeling.command.after.AfterCommandFunctionMetadata
import me.ahoo.wow.modeling.command.after.AfterCommandFunctionMetadata.Companion.toAfterCommandFunctionMetadata
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.matedata.CommandAggregateMetadata
import me.ahoo.wow.modeling.matedata.StateAggregateMetadata
import reactor.core.publisher.Mono
import java.lang.reflect.Constructor
import kotlin.reflect.KFunction
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.javaConstructor
import kotlin.reflect.jvm.javaType

/**
 * Aggregate Metadata Parser .
 *
 * @author ahoo wang
 */
object AggregateMetadataParser : CacheableMetadataParser() {
    override fun <TYPE : Any, M : Metadata> parseToMetadata(type: Class<TYPE>): M {
        val visitor = AggregateMetadataVisitor<TYPE, Any>(type)
        type.kotlin.visit(visitor)
        @Suppress("UNCHECKED_CAST")
        return visitor.toMetadata() as M
    }

    private class AggregateMetadataVisitor<C : Any, S : Any>(commandAggregateType: Class<C>) :
        ClassVisitor<C, AggregateMetadata<C, S>> {
        companion object {
            private val log = KotlinLogging.logger {}
        }

        private val commandAggregateType: Class<C>
        private val stateAggregateType: Class<S>
        private val stateAggregateMetadata: StateAggregateMetadata<S>
        private val constructor: Constructor<C>
        private val commandFunctionRegistry: MutableMap<Class<*>, FunctionAccessorMetadata<C, Mono<*>>> = HashMap()
        private val errorFunctionRegistry: MutableMap<Class<*>, FunctionAccessorMetadata<C, Mono<*>>> = HashMap()
        private val afterCommandFunctionRegistry: MutableList<AfterCommandFunctionMetadata<C>> = mutableListOf()

        init {
            try {
                constructor = commandAggregateType.kotlin.constructors.first {
                    it.parameters.count() == 1
                }.javaConstructor as Constructor<C>
            } catch (e: NoSuchElementException) {
                throw IllegalStateException(
                    "Failed to parse CommandAggregate[$commandAggregateType] metadata: Not defined Constructor[ctor(aggregateId) or ctor(stateAggregate)].",
                )
            }

            this.commandAggregateType = commandAggregateType

            val ctorParameterType = constructor.parameterTypes[0]

            @Suppress("UNCHECKED_CAST")
            stateAggregateType =
                (if (String::class.java != ctorParameterType) ctorParameterType else commandAggregateType)
                    as Class<S>
            stateAggregateMetadata = stateAggregateType.stateAggregateMetadata()
        }

        override fun visitFunction(function: KFunction<*>) {
            if (function.hasAnnotation<OnCommand>() ||
                function.isOnCommandFunction()
            ) {
                val functionMetadata = function.toMonoFunctionMetadata<C, Any>()
                commandFunctionRegistry.putIfAbsent(functionMetadata.supportedType, functionMetadata)
            }

            if (function.hasAnnotation<OnError>() ||
                function.isOnErrorFunction()
            ) {
                val functionMetadata = function.toMonoFunctionMetadata<C, Void>()
                errorFunctionRegistry.putIfAbsent(functionMetadata.supportedType, functionMetadata)
            }

            if (function.hasAnnotation<AfterCommand>() ||
                function.isAfterCommandFunction()
            ) {
                val afterCommandFunctionMetadata =
                    function.toMonoFunctionMetadata<C, Any>().toAfterCommandFunctionMetadata()
                afterCommandFunctionRegistry.add(afterCommandFunctionMetadata)
            }
        }

        private fun KFunction<*>.isOnCommandFunction() = DEFAULT_ON_COMMAND_NAME == name &&
            valueParameters.isNotEmpty() &&
            returnType.javaType != Void.TYPE

        private fun KFunction<*>.isOnErrorFunction() = DEFAULT_ON_ERROR_NAME == name &&
            valueParameters.isNotEmpty()

        private fun KFunction<*>.isAfterCommandFunction() = DEFAULT_AFTER_COMMAND_NAME == name &&
            valueParameters.isNotEmpty()

        override fun toMetadata(): AggregateMetadata<C, S> {
            if (commandFunctionRegistry.isEmpty()) {
                log.warn {
                    "CommandAggregate[$commandAggregateType] requires at least one OnCommand function!"
                }
            }
            val namedAggregate = MetadataSearcher.typeNamedAggregate[commandAggregateType]
            checkNotNull(namedAggregate) {
                "Failed to parse CommandAggregate[$commandAggregateType] metadata: Not defined in the metadata resource file[$WOW_METADATA_RESOURCE_NAME]."
            }
            val mountedCommands = commandAggregateType.getAnnotation(AggregateRoot::class.java)?.commands?.map {
                it.java
            }.orEmpty()
            val commandAggregateMetadata = CommandAggregateMetadata(
                aggregateType = commandAggregateType,
                namedAggregate = namedAggregate,
                constructorAccessor = DefaultConstructorAccessor(constructor),
                mountedCommands = mountedCommands.toSet(),
                commandFunctionRegistry = commandFunctionRegistry,
                errorFunctionRegistry = errorFunctionRegistry,
                afterCommandFunctionRegistry = afterCommandFunctionRegistry.sortedByOrder()
            )

            val staticTenantId = commandAggregateType.kotlin.scanAnnotation<StaticTenantId>()?.tenantId
                ?: MetadataSearcher.getAggregate(namedAggregate)?.tenantId
            return AggregateMetadata(namedAggregate, staticTenantId, stateAggregateMetadata, commandAggregateMetadata)
        }
    }
}

fun <C : Any, S : Any> Class<out C>.aggregateMetadata(): AggregateMetadata<C, S> {
    return AggregateMetadataParser.parse(this)
}

inline fun <reified C : Any, S : Any> aggregateMetadata(): AggregateMetadata<C, S> {
    return C::class.java.aggregateMetadata()
}
