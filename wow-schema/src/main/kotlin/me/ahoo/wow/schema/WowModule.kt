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

import com.github.victools.jsonschema.generator.CustomDefinitionProviderV2
import com.github.victools.jsonschema.generator.FieldScope
import com.github.victools.jsonschema.generator.Module
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigPart
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.schema.definition.AggregatedDomainEventStreamDefinitionProvider
import me.ahoo.wow.schema.definition.BundledDefinitionProvider
import me.ahoo.wow.schema.definition.EnumTextDefinitionProvider
import me.ahoo.wow.schema.definition.FilterExpressionDefinitionProvider
import me.ahoo.wow.schema.definition.JsonNodeDefinitionProvider
import me.ahoo.wow.schema.definition.MapDefinitionProvider
import me.ahoo.wow.schema.definition.QueryFieldDefinitionProvider
import me.ahoo.wow.schema.definition.QuerySchemaValueDefinitionProvider
import me.ahoo.wow.schema.definition.ServerSentEventCustomDefinitionProvider
import me.ahoo.wow.schema.definition.TypedDefaultValueModule
import me.ahoo.wow.schema.definition.WrappedDefinitionProvider
import me.ahoo.wow.schema.typed.AggregatedDomainEventStream

/** Wow's annotations (`@Summary`, `@Description`, command route variables) and framework type definitions. */
class WowModule(
    private val options: Set<WowOption> = WowOption.ALL
) : Module {
    private companion object {
        /** Order matters: the first provider that answers for a type wins. */
        val PROVIDERS: List<CustomDefinitionProviderV2> = listOf(
            BundledDefinitionProvider(AggregateId::class.java),
            WrappedDefinitionProvider.message(CommandMessage::class.java),
            WrappedDefinitionProvider.message(DomainEvent::class.java),
            BundledDefinitionProvider(
                DomainEventStream::class.java,
                excludedSubtypes = setOf(StateEvent::class.java, AggregatedDomainEventStream::class.java),
            ),
            AggregatedDomainEventStreamDefinitionProvider,
            WrappedDefinitionProvider.state(StateAggregate::class.java),
            WrappedDefinitionProvider.state(Snapshot::class.java),
            WrappedDefinitionProvider.state(StateEvent::class.java),
            ServerSentEventCustomDefinitionProvider,
            QueryFieldDefinitionProvider,
            QuerySchemaValueDefinitionProvider,
            JsonNodeDefinitionProvider,
            MapDefinitionProvider,
            EnumTextDefinitionProvider,
        )
    }

    override fun applyToConfigBuilder(builder: SchemaGeneratorConfigBuilder) {
        TypedDefaultValueModule.applyToConfigBuilder(builder)
        val generalConfigPart = builder.forTypesInGeneral()
        generalConfigPart.withCustomDefinitionProvider(FilterExpressionDefinitionProvider)
        val fieldConfigPart = builder.forFields()
        fieldConfigPart.withTargetTypeOverridesResolver(FilterExpressionDefinitionProvider::skipSubtypeLookup)
        builder.forMethods().withTargetTypeOverridesResolver(FilterExpressionDefinitionProvider::skipSubtypeLookup)
        fieldConfigPart.withTitleResolver(SummaryTitleFieldResolver)
        fieldConfigPart.withDescriptionResolver(DescriptionFieldResolver)
        ignoreCommandRouteVariable(fieldConfigPart)
        generalConfigPart.withTitleResolver(SummaryTitleTypeResolver)
        generalConfigPart.withDescriptionResolver(DescriptionTypeResolver)
        PROVIDERS.forEach(generalConfigPart::withCustomDefinitionProvider)
    }

    private fun ignoreCommandRouteVariable(configPart: SchemaGeneratorConfigPart<FieldScope>) {
        if (options.contains(WowOption.IGNORE_COMMAND_ROUTE_VARIABLE).not()) {
            return
        }

        configPart.withIgnoreCheck(IgnoreCommandRouteVariableCheck)
    }
}
