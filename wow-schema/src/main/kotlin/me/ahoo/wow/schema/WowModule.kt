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
import com.github.victools.jsonschema.generator.SchemaGeneratorGeneralConfigPart
import me.ahoo.wow.schema.joda.money.CurrencyUnitDefinitionProvider
import me.ahoo.wow.schema.joda.money.MoneyDefinitionProvider
import me.ahoo.wow.schema.kotlin.KotlinCustomDefinitionProvider
import me.ahoo.wow.schema.kotlin.KotlinNullableCheck
import me.ahoo.wow.schema.kotlin.KotlinReadOnlyCheck
import me.ahoo.wow.schema.kotlin.KotlinRequiredCheck
import me.ahoo.wow.schema.kotlin.KotlinWriteOnlyCheck
import me.ahoo.wow.schema.typed.AggregateIdDefinitionProvider
import me.ahoo.wow.schema.typed.CommandDefinitionProvider
import me.ahoo.wow.schema.typed.DomainEventDefinitionProvider
import me.ahoo.wow.schema.typed.DomainEventStreamDefinitionProvider
import me.ahoo.wow.schema.typed.SnapshotDefinitionProvider
import me.ahoo.wow.schema.typed.StateAggregateDefinitionProvider
import me.ahoo.wow.schema.typed.StateEventDefinitionProvider

class WowModule(private val options: Set<WowOption> = WowOption.ALL) : Module {
    override fun applyToConfigBuilder(builder: SchemaGeneratorConfigBuilder) {
        val fieldConfigPart = builder.forFields()
        ignoreCommandRouteVariable(fieldConfigPart)
        val generalConfigPart = builder.forTypesInGeneral()
        kotlinNullable(fieldConfigPart, generalConfigPart)
        generalConfigPart.withCustomDefinitionProvider(AggregateIdDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(CommandDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(DomainEventDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(DomainEventStreamDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(StateAggregateDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(SnapshotDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(StateEventDefinitionProvider)
        wowNamingStrategy(generalConfigPart)
        jodaMoney(generalConfigPart)
    }

    private fun ignoreCommandRouteVariable(configPart: SchemaGeneratorConfigPart<FieldScope>) {
        if (options.contains(WowOption.IGNORE_COMMAND_ROUTE_VARIABLE).not()) {
            return
        }

        configPart.withIgnoreCheck(IgnoreCommandRouteVariableCheck)
    }

    private fun kotlinNullable(
        fieldConfigPart: SchemaGeneratorConfigPart<FieldScope>,
        generalConfigPart: SchemaGeneratorGeneralConfigPart
    ) {
        if (options.contains(WowOption.KOTLIN).not()) {
            return
        }
        fieldConfigPart.withNullableCheck(KotlinNullableCheck)
        fieldConfigPart.withReadOnlyCheck(KotlinReadOnlyCheck)
        fieldConfigPart.withRequiredCheck(KotlinRequiredCheck)
        fieldConfigPart.withWriteOnlyCheck(KotlinWriteOnlyCheck)
        generalConfigPart.withCustomDefinitionProvider(KotlinCustomDefinitionProvider)
    }

    private fun wowNamingStrategy(generalConfigPart: SchemaGeneratorGeneralConfigPart) {
        if (options.contains(WowOption.WOW_NAMING_STRATEGY).not()) {
            return
        }
        generalConfigPart.withDefinitionNamingStrategy(WowSchemaNamingStrategy)
    }

    private fun jodaMoney(generalConfigPart: SchemaGeneratorGeneralConfigPart) {
        if (options.contains(WowOption.JODA_MONEY).not()) {
            return
        }
        generalConfigPart.withCustomDefinitionProvider(CurrencyUnitDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(MoneyDefinitionProvider)
    }
}
