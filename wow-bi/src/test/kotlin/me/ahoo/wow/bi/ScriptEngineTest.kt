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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.MetadataSearcher
import org.junit.jupiter.api.Test

class ScriptEngineTest {
    private val aggregate = MetadataSearcher.localAggregates.single { it.aggregateName == "aggregate" }
    private val aggregates: Set<NamedAggregate> = setOf(aggregate)

    @Test
    fun `should generate script`() {
        val syncScript = ScriptEngine.generate(MetadataSearcher.localAggregates)

        syncScript.assert().contains("-- global --")
        syncScript.assert().contains("-- clear --")
        syncScript.assert().contains("-- bi.aggregate.clear --")
        syncScript.assert().contains("-- bi.aggregate.command --")
        syncScript.assert().contains("-- bi.aggregate.stateEvent --")
        syncScript.assert().contains("-- bi.aggregate.stateLast --")
        syncScript.assert().contains("-- bi.aggregate.expansion --")
        syncScript.assert().contains("ENGINE = Kafka('localhost:9093'")
        syncScript.assert().contains("'wow.bi.aggregate.command'")
    }

    @Test
    fun `should generate script with custom parameters`() {
        val syncScript = ScriptEngine.generate(
            MetadataSearcher.localAggregates,
            "kafkaBootstrapServers",
            "topicPrefix",
        )

        syncScript.assert().contains("-- global --")
        syncScript.assert().contains("ENGINE = Kafka('kafkaBootstrapServers'")
        syncScript.assert().contains("'topicPrefix")
    }

    @Test
    fun `should preserve legacy generation with blank Kafka and topic values`() {
        val syncScript = ScriptEngine.generate(
            aggregates,
            kafkaBootstrapServers = "",
            topicPrefix = "",
        )

        syncScript.assert().contains("ENGINE = Kafka(''")
        syncScript.assert().contains("'bi.aggregate.command'")
    }

    @Test
    fun `should strictly validate options based generation`() {
        ScriptEngine.runCatching {
            generate(aggregates, BiScriptOptions(kafkaBootstrapServers = ""))
        }.isFailure.assert().isTrue()
        ScriptEngine.runCatching {
            generateResult(aggregates, BiScriptOptions(topicPrefix = ""))
        }.isFailure.assert().isTrue()
    }

    @Test
    fun `should expose compatible and options based facade methods`() {
        val methods = ScriptEngine::class.java.methods.map { method ->
            method.name to method.parameterTypes.toList()
        }.toSet()

        methods.assert().contains(
            "generate" to listOf(Set::class.java, String::class.java, String::class.java),
            "generate" to listOf(Set::class.java, BiScriptOptions::class.java),
            "generateResult" to listOf(Set::class.java, BiScriptOptions::class.java),
            "generateResult" to listOf(Set::class.java, String::class.java, String::class.java),
        )
    }

    @Test
    fun `should keep legacy result overload non defaulted and aligned`() {
        val result = ScriptEngine.generateResult(aggregates, "kafka", "prefix.")

        result.script.assert().isEqualTo(ScriptEngine.generate(aggregates, "kafka", "prefix."))
    }
}
