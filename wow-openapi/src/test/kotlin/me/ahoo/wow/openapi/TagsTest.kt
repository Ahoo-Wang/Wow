package me.ahoo.wow.openapi

import io.swagger.v3.oas.annotations.tags.Tag
import me.ahoo.wow.openapi.Tags.asTags
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

@Tag(name = "test")
@Tag(name = "test2")
class TagsTest {

    @Test
    fun asTags() {
        val tags = TagsTest::class.java.asTags()
        assertThat(tags, equalTo(setOf("test", "test2")))
    }
}
