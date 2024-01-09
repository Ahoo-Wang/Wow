package me.ahoo.wow.bi

import me.ahoo.wow.configuration.MetadataSearcher
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class ScriptEngineTest {

    @Test
    fun generate() {
        val syncScript = ScriptEngine.generate(MetadataSearcher.localAggregates)
        assertThat(syncScript, notNullValue())
    }
}
