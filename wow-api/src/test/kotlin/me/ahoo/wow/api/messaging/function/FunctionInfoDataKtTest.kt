package me.ahoo.wow.api.messaging.function

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class FunctionInfoDataKtTest {

    @Test
    fun materialize() {
        val functionInfoData = FunctionInfoData(FunctionKind.COMMAND, "contextName", "processorName", "functionName")
        val materialized = functionInfoData.materialize()
        functionInfoData.assert().isSameAs(materialized)
    }
}
