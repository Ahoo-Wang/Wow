package me.ahoo.wow.bi

import me.ahoo.test.asserts.assert
import me.ahoo.wow.configuration.MetadataSearcher
import org.junit.jupiter.api.Test

class ScriptEngineTest {

    @Test
    fun generate() {
        val syncScript = ScriptEngine.generate(MetadataSearcher.localAggregates)
        syncScript.assert().isNotNull()
    }

    @Test
    fun generateUseCustomParameters() {
        val syncScript = ScriptEngine.generate(
            MetadataSearcher.localAggregates,
            "kafkaBootstrapServers",
            "topicPrefix",
            MessageHeaderSqlType.STRING
        )
        syncScript.assert().isNotNull()
    }
}
