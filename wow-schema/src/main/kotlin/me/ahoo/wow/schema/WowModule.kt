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

import com.github.victools.jsonschema.generator.FieldScope
import com.github.victools.jsonschema.generator.Module
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigPart
import me.ahoo.wow.schema.typed.AggregateIdDefinitionProvider
import me.ahoo.wow.schema.typed.AggregatedDomainEventStreamDefinitionProvider
import me.ahoo.wow.schema.typed.AggregatedFieldsDefinitionProvider
import me.ahoo.wow.schema.typed.CommandDefinitionProvider
import me.ahoo.wow.schema.typed.DomainEventDefinitionProvider
import me.ahoo.wow.schema.typed.DomainEventStreamDefinitionProvider
import me.ahoo.wow.schema.typed.SnapshotDefinitionProvider
import me.ahoo.wow.schema.typed.StateAggregateDefinitionProvider
import me.ahoo.wow.schema.typed.StateEventDefinitionProvider
import me.ahoo.wow.schema.typed.query.AggregatedListQueryDefinitionProvider
import me.ahoo.wow.schema.typed.query.AggregatedPagedQueryDefinitionProvider
import me.ahoo.wow.schema.typed.query.AggregatedSingleQueryDefinitionProvider
import me.ahoo.wow.schema.typed.query.ConditionOptionsDefinitionProvider
import me.ahoo.wow.schema.web.ServerSentEventCustomDefinitionProvider

class WowModule(
    private val options: Set<WowOption> = WowOption.ALL
) :
    Module {
    override fun applyToConfigBuilder(builder: SchemaGeneratorConfigBuilder) {
        val fieldConfigPart = builder.forFields()
        fieldConfigPart.withTitleResolver(SummaryTitleResolver)
        fieldConfigPart.withDescriptionResolver(DescriptionResolver)
        ignoreCommandRouteVariable(fieldConfigPart)
        val generalConfigPart = builder.forTypesInGeneral()
        generalConfigPart.withCustomDefinitionProvider(AggregateIdDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(CommandDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(DomainEventDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(DomainEventStreamDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(AggregatedDomainEventStreamDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(AggregatedFieldsDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(AggregatedListQueryDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(AggregatedPagedQueryDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(AggregatedSingleQueryDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(StateAggregateDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(SnapshotDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(StateEventDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(ServerSentEventCustomDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(ConditionOptionsDefinitionProvider)
    }

    private fun ignoreCommandRouteVariable(configPart: SchemaGeneratorConfigPart<FieldScope>) {
        if (options.contains(WowOption.IGNORE_COMMAND_ROUTE_VARIABLE).not()) {
            return
        }

        configPart.withIgnoreCheck(IgnoreCommandRouteVariableCheck)
    }
}
