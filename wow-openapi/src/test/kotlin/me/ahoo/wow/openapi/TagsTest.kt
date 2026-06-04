package me.ahoo.wow.openapi

import io.swagger.v3.oas.annotations.tags.Tag
import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.Tags.toTags
import org.junit.jupiter.api.Test

class TagsTest {

    @Test
    fun `should convert single tag annotation to tags`() {
        val tags = SingleTag::class.java.toTags()
        tags.map { it.name }.assert().contains("test")
    }

    @Test
    fun `should convert multiple tag annotations to tags`() {
        val tags = MultiTag::class.java.toTags()
        tags.map { it.name }.assert().contains("test", "test2")
    }
}

@Tag(name = "test")
@Tag(name = "test2")
interface MultiTag

@Tag(name = "test")
interface SingleTag
