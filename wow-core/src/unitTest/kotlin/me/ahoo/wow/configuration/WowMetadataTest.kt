package me.ahoo.wow.configuration

import org.junit.jupiter.api.Test

class WowMetadataTest {

    @Test
    fun `should merge wow metadata`() {
        WowMetadata().merge(WowMetadata())
    }

    @Test
    fun `should merge empty context`() {
        WowMetadata(mapOf("test" to BoundedContext())).merge(WowMetadata(mapOf("test" to BoundedContext())))
    }

    @Test
    fun `should merge context when alias is empty`() {
        WowMetadata(mapOf("test" to BoundedContext(""))).merge(WowMetadata(mapOf("test" to BoundedContext(""))))
    }

    @Test
    fun `should merge context when alias is empty and other is null`() {
        WowMetadata(mapOf("test" to BoundedContext(""))).merge(WowMetadata(mapOf("test" to BoundedContext())))
    }
}
