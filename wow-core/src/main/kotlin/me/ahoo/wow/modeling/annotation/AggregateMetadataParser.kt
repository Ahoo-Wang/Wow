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

import me.ahoo.wow.api.annotation.DEFAULT_ON_COMMAND_NAME
import me.ahoo.wow.api.annotation.DEFAULT_ON_ERROR_NAME
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.annotation.OnError
import me.ahoo.wow.api.annotation.StaticTenantId
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.configuration.WOW_METADATA_RESOURCE_NAME
import me.ahoo.wow.infra.accessor.constructor.DefaultConstructorAccessor
import me.ahoo.wow.infra.reflection.AnnotationScanner.scan
import me.ahoo.wow.infra.reflection.ClassMetadata
import me.ahoo.wow.infra.reflection.ClassVisitor
import me.ahoo.wow.messaging.function.MethodFunctionMetadata
import me.ahoo.wow.messaging.function.asMonoFunctionMetadata
import me.ahoo.wow.metadata.CacheableMetadataParser
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.matedata.CommandAggregateMetadata
import me.ahoo.wow.modeling.matedata.StateAggregateMetadata
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import java.lang.reflect.Constructor
import java.lang.reflect.Method

private val log = LoggerFactory.getLogger(AggregateMetadataParser::class.java)

/**
 * Aggregate Metadata Parser .
 *
 * @author ahoo wang
 */
object AggregateMetadataParser : CacheableMetadataParser<Class<*>, AggregateMetadata<*, *>>() {

    override fun parseAsMetadata(type: Class<*>): AggregateMetadata<*, *> {
        @Suppress("UNCHECKED_CAST")
        val visitor = AggregateMetadataVisitor<Any, Any>(type as Class<Any>)
        ClassMetadata.visit(type, visitor)
        return visitor.asMetadata()
    }

    private class AggregateMetadataVisitor<C : Any, S : Any>(commandAggregateType: Class<C>) :
        ClassVisitor {
        private val commandAggregateType: Class<C>
        private val stateAggregateMetadata: StateAggregateMetadata<S>
        private val staticTenantId: String?
        private var constructor: Constructor<C>
        private var commandFunctionRegistry: MutableMap<Class<*>, MethodFunctionMetadata<C, Mono<*>>> = HashMap()
        private var errorFunctionRegistry: MutableMap<Class<*>, MethodFunctionMetadata<C, Mono<*>>> = HashMap()

        init {
            constructor =
                commandAggregateType.declaredConstructors
                    .map {
                        @Suppress("UNCHECKED_CAST")
                        it as Constructor<C>
                    }
                    .firstOrNull { it.parameterCount == 1 }
                    ?: throw IllegalStateException(
                        "Failed to parse CommandAggregate[$commandAggregateType] metadata: Not defined Constructor[ctor(aggregateId) or ctor(stateAggregate)].",
                    )

            this.commandAggregateType = commandAggregateType

            val ctorParameterType = constructor.parameterTypes[0]

            @Suppress("UNCHECKED_CAST")
            val stateAggregateType =
                (if (String::class.java != ctorParameterType) ctorParameterType else commandAggregateType)
                    as Class<S>
            stateAggregateMetadata = stateAggregateType.asStateAggregateMetadata()
            staticTenantId = commandAggregateType.scan<StaticTenantId>()?.tenantId
                ?: stateAggregateType.scan<StaticTenantId>()?.tenantId
        }

        override fun visitMethod(method: Method) {
            if (method.isAnnotationPresent(OnCommand::class.java) ||
                (isOnCommandFunctionMethod(method))
            ) {
                val functionMetadata = method.asMonoFunctionMetadata<C, Any>()
                commandFunctionRegistry.putIfAbsent(functionMetadata.supportedType, functionMetadata)
            }

            if (method.isAnnotationPresent(OnError::class.java) ||
                (isOnErrorFunctionMethod(method))
            ) {
                val functionMetadata = method.asMonoFunctionMetadata<C, Void>()
                errorFunctionRegistry.putIfAbsent(functionMetadata.supportedType, functionMetadata)
            }
        }

        private fun isOnCommandFunctionMethod(method: Method) = DEFAULT_ON_COMMAND_NAME == method.name &&
            method.parameterCount > 0 &&
            method.returnType != Void.TYPE

        private fun isOnErrorFunctionMethod(method: Method) = DEFAULT_ON_ERROR_NAME == method.name &&
            method.parameterCount > 0

        fun asMetadata(): AggregateMetadata<C, S> {
            if (commandFunctionRegistry.isEmpty()) {
                if (log.isWarnEnabled) {
                    log.warn("CommandAggregate[$commandAggregateType] requires at least one OnCommand function!")
                }
            }
            val namedAggregate = MetadataSearcher.typeNamedAggregate[commandAggregateType]
            checkNotNull(namedAggregate) {
                "Failed to parse CommandAggregate[$commandAggregateType] metadata: Not defined in the metadata resource file[$WOW_METADATA_RESOURCE_NAME]."
            }
            val commandAggregateMetadata = CommandAggregateMetadata(
                aggregateType = commandAggregateType,
                namedAggregate = namedAggregate,
                constructorAccessor = DefaultConstructorAccessor(constructor),
                commandFunctionRegistry = commandFunctionRegistry,
                errorFunctionRegistry = errorFunctionRegistry,
            )
            return AggregateMetadata(namedAggregate, staticTenantId, stateAggregateMetadata, commandAggregateMetadata)
        }
    }
}

fun <C : Any, S : Any> Class<out C>.asAggregateMetadata(): AggregateMetadata<C, S> {
    @Suppress("UNCHECKED_CAST")
    return AggregateMetadataParser.parse(this) as AggregateMetadata<C, S>
}

inline fun <reified C : Any, S : Any> aggregateMetadata(): AggregateMetadata<C, S> {
    return C::class.java.asAggregateMetadata()
}
