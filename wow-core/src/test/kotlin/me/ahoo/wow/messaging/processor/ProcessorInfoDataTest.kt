package me.ahoo.wow.messaging.processor

import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class ProcessorInfoDataTest {
    @Test
    fun `materialize - ProcessorInfoData`() {
        val processorInfoData = ProcessorInfoData("contextName", "processorName")
        val materialized = processorInfoData.materialize()
        assertThat(processorInfoData, sameInstance(materialized))
    }

    @Test
    fun `unknown - ProcessorInfoData`() {
        val processorInfoData = ProcessorInfoData.unknown("contextName")
        assertThat(processorInfoData.processorName, equalTo("Unknown"))
    }
}