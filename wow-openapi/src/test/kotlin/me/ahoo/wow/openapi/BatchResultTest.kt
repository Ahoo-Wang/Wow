package me.ahoo.wow.openapi

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class BatchResultTest {
    @Test
    fun test() {
        val batchResult = BatchResult("cursorId", 1)
        batchResult.afterId.assert().isEqualTo("cursorId")
        batchResult.size.assert().isEqualTo(1)
    }
}
