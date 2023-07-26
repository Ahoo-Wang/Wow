package me.ahoo.wow.openapi

import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class BatchResultTest {
    @Test
    fun test() {
        val batchResult = BatchResult("cursorId", 1)
        assertThat(batchResult.cursorId, equalTo("cursorId"))
        assertThat(batchResult.size, equalTo(1))
    }
}