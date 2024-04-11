package me.ahoo.wow.query

import me.ahoo.wow.api.query.Projection
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class ProjectionDslTest {

    @Test
    fun build() {
        val projection = projection {
            include("field1")
            exclude("field2")
        }
        assertThat(
            projection,
            equalTo(
                Projection(
                    include = listOf("field1"),
                    exclude = listOf("field2")
                )
            )
        )
    }
}
