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

package me.ahoo.wow.event

import me.ahoo.wow.event.upgrader.EventNamedAggregate
import me.ahoo.wow.event.upgrader.EventUpgrader
import me.ahoo.wow.event.upgrader.MaterializedEventNamedAggregate
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.event.DelegatingDomainEventRecord
import me.ahoo.wow.serialization.event.DomainEventRecord
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import tools.jackson.databind.node.ObjectNode

@State(Scope.Benchmark)
open class EventUpgraderBenchmark {
    @Param("1", "3", "5")
    var upgraderCount: Int = 1

    private lateinit var upgraders: List<BenchmarkEventUpgrader>
    private lateinit var eventRecord: DomainEventRecord

    @Setup
    fun setup() {
        val namedAggregate = MaterializedNamedAggregate("benchmark", "aggregate")
        upgraders = (1..upgraderCount).map { index ->
            BenchmarkEventUpgrader(
                eventNamedAggregate = MaterializedEventNamedAggregate(namedAggregate, "EventV$index"),
            )
        }

        val bodyNode: ObjectNode = JsonSerializer.createObjectNode()
        bodyNode.put("field1", "value1")
        bodyNode.put("version", 1)
        val recordNode: ObjectNode = JsonSerializer.createObjectNode()
        recordNode.put("id", "event-id")
        recordNode.put("aggregateId", namedAggregate.aggregateId("agg-id").id)
        recordNode.put("contextName", namedAggregate.contextName)
        recordNode.put("aggregateName", namedAggregate.aggregateName)
        recordNode.put("version", 1)
        recordNode.put("commandId", "command-id")
        recordNode.put("bodyType", BenchmarkEventBody::class.java.name)
        recordNode.set("body", bodyNode)
        eventRecord = DelegatingDomainEventRecord(recordNode)
    }

    @Benchmark
    fun upgradePipeline(blackhole: Blackhole) {
        var current = eventRecord
        for (upgrader in upgraders) {
            current = upgrader.upgrade(current)
        }
        blackhole.consume(current)
    }
}

private data class BenchmarkEventBody(val field1: String = "", val version: Int = 1)

private class BenchmarkEventUpgrader(
    override val eventNamedAggregate: EventNamedAggregate,
) : EventUpgrader {
    override fun upgrade(domainEventRecord: DomainEventRecord): DomainEventRecord {
        return domainEventRecord
    }
}
