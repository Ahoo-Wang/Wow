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
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import kotlin.reflect.jvm.kotlinProperty

class WowModule(private val options: Set<WowOption>) : Module {
    override fun applyToConfigBuilder(builder: SchemaGeneratorConfigBuilder) {
        val fieldConfigPart = builder.forFields()
        ignoreCommandRouteVariable(fieldConfigPart)
        val generalConfigPart = builder.forTypesInGeneral()
        generalConfigPart.withCustomDefinitionProvider(AggregateIdDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(CommandDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(DomainEventDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(DomainEventStreamDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(StateAggregateDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(SnapshotDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(StateEventDefinitionProvider)
    }

    private fun ignoreCommandRouteVariable(configPart: SchemaGeneratorConfigPart<FieldScope>) {
        if (options.contains(WowOption.IGNORE_COMMAND_ROUTE_VARIABLE).not()) {
            return
        }

        configPart.withIgnoreCheck {
            val property = it.rawMember.kotlinProperty!!
            if (property.scanAnnotation<CommandRoute.PathVariable>() != null) {
                return@withIgnoreCheck true
            }
            if (property.scanAnnotation<CommandRoute.HeaderVariable>() != null) {
                return@withIgnoreCheck true
            }
            false
        }
    }
}
