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

package me.ahoo.wow.bi

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.bi.expansion.StateExpansionScriptGenerator.Companion.toScriptGenerator
import me.ahoo.wow.modeling.toStringWithAlias

object ScriptEngine {

    fun generate(
        namedAggregates: Set<NamedAggregate>,
        kafkaBootstrapServers: String = ScriptTemplateEngine.DEFAULT_KAFKA_BOOTSTRAP_SERVERS,
        topicPrefix: String = ScriptTemplateEngine.DEFAULT_TOPIC_PREFIX,
        headerType: String = ScriptTemplateEngine.DEFAULT_HEADER_TYPE
    ): String {
        val scriptGenerators = buildMap {
            namedAggregates.forEach { namedAggregate ->
                val scriptGenerator = namedAggregate.toScriptGenerator()
                put(namedAggregate, scriptGenerator)
            }
        }
        return buildString {
            appendLine("-- global --")
            appendLine(ScriptTemplateEngine.renderGlobal())
            appendLine("-- global --")
            appendLine("-- clear --")
            namedAggregates.forEach { namedAggregate ->
                appendLine("-- ${namedAggregate.toStringWithAlias()}.clear --")
                appendLine(
                    ScriptTemplateEngine.renderClear(
                        namedAggregate = namedAggregate,
                        expansionTables = requireNotNull(scriptGenerators[namedAggregate]).targetTables
                    )
                )
                appendLine("-- ${namedAggregate.toStringWithAlias()}.clear --")
            }
            appendLine("-- clear --")
            namedAggregates.forEach { namedAggregate ->
                appendLine("-- ${namedAggregate.toStringWithAlias()}.command --")
                appendLine(
                    ScriptTemplateEngine.renderCommand(
                        namedAggregate = namedAggregate,
                        kafkaBootstrapServers = kafkaBootstrapServers,
                        topicPrefix = topicPrefix,
                        headerType = headerType
                    )
                )
                appendLine("-- ${namedAggregate.toStringWithAlias()}.command --")
                appendLine("-- ${namedAggregate.toStringWithAlias()}.stateEvent --")
                appendLine(
                    ScriptTemplateEngine.renderStateEvent(
                        namedAggregate = namedAggregate,
                        kafkaBootstrapServers = kafkaBootstrapServers,
                        topicPrefix = topicPrefix,
                        headerType = headerType
                    )
                )
                appendLine("-- ${namedAggregate.toStringWithAlias()}.stateEvent --")
                appendLine("-- ${namedAggregate.toStringWithAlias()}.stateLast --")
                appendLine(ScriptTemplateEngine.renderStateLast(
                    namedAggregate = namedAggregate,
                    headerType = headerType
                ))
                appendLine("-- ${namedAggregate.toStringWithAlias()}.stateLast --")
                appendLine("-- ${namedAggregate.toStringWithAlias()}.expansion --")
                appendLine(requireNotNull(scriptGenerators[namedAggregate]).toString())
                appendLine("-- ${namedAggregate.toStringWithAlias()}.expansion --")
            }
        }
    }
}
