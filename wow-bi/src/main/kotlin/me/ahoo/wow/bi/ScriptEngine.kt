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

object ScriptEngine {
    fun generate(
        namedAggregates: Set<NamedAggregate>,
        kafkaBootstrapServers: String = ScriptTemplateEngine.DEFAULT_KAFKA_BOOTSTRAP_SERVERS,
        topicPrefix: String = ScriptTemplateEngine.DEFAULT_TOPIC_PREFIX,
    ): String = generateResult(namedAggregates, kafkaBootstrapServers, topicPrefix).script

    fun generate(namedAggregates: Set<NamedAggregate>, options: BiScriptOptions): String =
        generateResult(namedAggregates, options).script

    fun generateResult(
        namedAggregates: Set<NamedAggregate>,
        options: BiScriptOptions = BiScriptOptions(),
    ): BiScriptResult = BiScriptGenerator(options).generate(namedAggregates)

    fun generateResult(
        namedAggregates: Set<NamedAggregate>,
        kafkaBootstrapServers: String,
        topicPrefix: String,
    ): BiScriptResult = BiScriptGenerator.legacy(
        BiScriptOptions(
            kafkaBootstrapServers = kafkaBootstrapServers,
            topicPrefix = topicPrefix,
        )
    ).generate(namedAggregates)
}
