package me.ahoo.wow.openapi.route

import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class CommandRouteMetadataTest {

    @Test
    fun equalTo() {
        val commandRouteMetadata = commandRouteMetadata<MockCommandRoute>()
        assertThat(commandRouteMetadata, equalTo(commandRouteMetadata))
    }

    @Test
    fun equalToAny() {
        val commandRouteMetadata = commandRouteMetadata<MockCommandRoute>()
        assertThat(commandRouteMetadata, not(Any()))
    }

    @Test
    fun equalToOther() {
        val commandRouteMetadata = commandRouteMetadata<MockCommandRoute>()
        val nestedMockCommandRoute = commandRouteMetadata<NestedMockCommandRoute>()
        assertThat(commandRouteMetadata, not(nestedMockCommandRoute))
    }
}
