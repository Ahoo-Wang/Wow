package me.ahoo.wow.openapi

import io.swagger.v3.oas.annotations.tags.Tag
import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.Tags.toTags
import org.junit.jupiter.api.Test

@Tag(name = "test")
@Tag(name = "test2")
class TagsTest {

    @Test
    fun asTags() {
        val tags = TagsTest::class.java.toTags()
        tags.assert().contains("test", "test2")
    }
}
