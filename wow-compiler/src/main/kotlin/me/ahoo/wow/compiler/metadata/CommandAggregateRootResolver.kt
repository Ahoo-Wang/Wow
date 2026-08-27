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

package me.ahoo.wow.compiler.metadata

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getKotlinClassByName
import com.google.devtools.ksp.isAnnotationPresent
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.DEFAULT_ON_COMMAND_NAME
import me.ahoo.wow.api.annotation.DEFAULT_ON_SOURCING_NAME
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.annotation.OnSourcing
import me.ahoo.wow.api.annotation.StaticTenantId
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.compiler.AggregateRootResolver.resolveAggregateRootMetadata
import me.ahoo.wow.compiler.metadata.BoundedContextResolver.getAnnotation
import me.ahoo.wow.compiler.metadata.BoundedContextResolver.getArgumentValue
import me.ahoo.wow.configuration.Aggregate
import me.ahoo.wow.messaging.handler.MessageExchange

object CommandAggregateRootResolver {

    @OptIn(KspExperimental::class)
    fun KSClassDeclaration.resolveAggregateRoot(resolver: Resolver): Aggregate {
        val aggregateRootMetadata = this.resolveAggregateRootMetadata()

        val commands = getAllFunctions()
            .filter { it.isCommand() }
            .map {
                it.toMessageType(resolver)
            }

        val mountCommands = aggregateRootMetadata.command.getAnnotation(AggregateRoot::class)
            ?.getArgumentValue<List<KSType>>(AggregateRoot::commands.name)
            ?.mapNotNull { it.declaration.qualifiedName?.asString() }
            ?.toSet()
            .orEmpty()

        val commandReturnEvents = getAllFunctions()
            .filter { it.isAnnotationPresent(OnCommand::class) }
            .flatMap { commandFunction ->
                commandFunction.getAnnotation(OnCommand::class)
                    ?.getArgumentValue<List<KSType>>(OnCommand::returns.name)
                    ?.mapNotNull { it.declaration.qualifiedName?.asString() }
                    ?.toSet()
                    .orEmpty()
            }

        val sourcingEvents = aggregateRootMetadata.state.getAllFunctions()
            .filter { it.isDomainEvent() }
            .map {
                it.toMessageType(resolver)
            }

        val tenantId =
            getAnnotation(StaticTenantId::class)?.getArgumentValue<String>(StaticTenantId::tenantId.name)
                ?: getAllSuperTypes().mapNotNull {
                    it.declaration.getAnnotation(StaticTenantId::class)?.getArgumentValue<String>(
                        StaticTenantId::tenantId.name
                    )
                }.firstOrNull()

        return Aggregate(
            type = aggregateRootMetadata.type,
            scopes = setOf(this.packageName.asString()),
            tenantId = tenantId,
            commands = (commands + mountCommands).let {
                linkedSetOf<String>().apply {
                    addAll(it)
                }
            },
            events = (commandReturnEvents + sourcingEvents).let {
                linkedSetOf<String>().apply {
                    addAll(it)
                }
            }
        )
    }

    @OptIn(KspExperimental::class)
    fun KSFunctionDeclaration.isCommand(): Boolean {
        return (simpleName.getShortName() == DEFAULT_ON_COMMAND_NAME || isAnnotationPresent(OnCommand::class)) &&
            parameters.isNotEmpty() &&
            (returnType != null)
    }

    @OptIn(KspExperimental::class)
    fun KSFunctionDeclaration.isDomainEvent(): Boolean {
        return (simpleName.getShortName() == DEFAULT_ON_SOURCING_NAME || isAnnotationPresent(OnSourcing::class)) &&
            parameters.isNotEmpty()
    }

    private fun KSFunctionDeclaration.toMessageType(resolver: Resolver): String {
        return parameters.first().type.resolve().toMessageType(resolver)
    }

    @OptIn(KspExperimental::class)
    private fun KSType.toMessageType(resolver: Resolver): String {
        val messageDeclaration = resolver.getKotlinClassByName(Message::class.qualifiedName!!)!!
        val messageExchangeDeclaration = resolver.getKotlinClassByName(MessageExchange::class.qualifiedName!!)!!
        if (messageDeclaration.asStarProjectedType().isAssignableFrom(this) ||
            messageExchangeDeclaration.asStarProjectedType().isAssignableFrom(this)
        ) {
            return this.arguments.first().type!!.resolve().toMessageType(resolver)
        }
        return checkNotNull(this.declaration.qualifiedName).asString()
    }
}
