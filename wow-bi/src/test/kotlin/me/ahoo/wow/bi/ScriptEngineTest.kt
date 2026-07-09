package me.ahoo.wow.bi

import me.ahoo.test.asserts.assert
import me.ahoo.wow.configuration.MetadataSearcher
import org.junit.jupiter.api.Test

class ScriptEngineTest {

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
            "topicPrefix"
        )
        syncScript.assert().contains("-- global --")
        syncScript.assert().contains("ENGINE = Kafka('kafkaBootstrapServers'")
        syncScript.assert().contains("'topicPrefix")
    }
}
