package me.ahoo.wow.bi

import me.ahoo.test.asserts.assert
import me.ahoo.wow.configuration.MetadataSearcher
import org.junit.jupiter.api.Test

class ScriptEngineTest {

    @Test
    fun `should generate script`() {
        val syncScript = ScriptEngine.generate(MetadataSearcher.localAggregates)
        syncScript.assert().isNotNull()
    }

    @Test
    fun `should generate script with custom parameters`() {
        val syncScript = ScriptEngine.generate(
            MetadataSearcher.localAggregates,
            "kafkaBootstrapServers",
            "topicPrefix"
        )
        syncScript.assert().isNotNull()
    }
}
