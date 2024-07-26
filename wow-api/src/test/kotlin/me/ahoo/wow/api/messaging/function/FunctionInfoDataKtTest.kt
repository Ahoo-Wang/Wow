package me.ahoo.wow.api.messaging.function

import me.ahoo.wow.api.messaging.processor.materialize
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class FunctionInfoDataKtTest {

    @Test
    fun materialize() {
        val functionInfoData = FunctionInfoData(FunctionKind.COMMAND, "contextName", "processorName", "functionName")
        val materialized = functionInfoData.materialize()
        assertThat(functionInfoData, sameInstance(materialized))
    }
}
