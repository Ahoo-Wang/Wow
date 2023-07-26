package me.ahoo.wow.configuration

import org.junit.jupiter.api.Test

class WowMetadataTest {

    @Test
    fun merge() {
        WowMetadata().merge(WowMetadata())
    }

    @Test
    fun mergeEmptyContext() {
        WowMetadata(mapOf("test" to BoundedContext())).merge(WowMetadata(mapOf("test" to BoundedContext())))
    }

    @Test
    fun mergeContextIfEmtpyAlias() {
        WowMetadata(mapOf("test" to BoundedContext(""))).merge(WowMetadata(mapOf("test" to BoundedContext(""))))
    }

    @Test
    fun mergeContextIfEmtpyNullAlias() {
        WowMetadata(mapOf("test" to BoundedContext(""))).merge(WowMetadata(mapOf("test" to BoundedContext())))
    }
}
