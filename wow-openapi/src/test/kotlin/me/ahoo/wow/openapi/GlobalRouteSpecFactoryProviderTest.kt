package me.ahoo.wow.openapi

import io.mockk.mockk
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class GlobalRouteSpecFactoryProviderTest {

    @Test
    fun add() {
        val mockGlobalRouteSpecFactory = mockk<GlobalRouteSpecFactory>()
        GlobalRouteSpecFactoryProvider.add(mockGlobalRouteSpecFactory)
        assertThat(GlobalRouteSpecFactoryProvider.get().contains(mockGlobalRouteSpecFactory), equalTo(true))
        GlobalRouteSpecFactoryProvider.remove(mockGlobalRouteSpecFactory)
        assertThat(GlobalRouteSpecFactoryProvider.get().contains(mockGlobalRouteSpecFactory), equalTo(false))
    }
}
