package me.ahoo.wow.ioc

import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.ioc.SimpleServiceProviderTest.Companion.SERVICE_NAME
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

@Name(SERVICE_NAME)
class SimpleServiceProviderTest {
    companion object {
        const val SERVICE_NAME = "serviceProviderTest"
    }

    @Test
    fun getService() {
        val serviceProvider = SimpleServiceProvider()
        serviceProvider.register(this)
        assertThat(serviceProvider.getRequiredService<SimpleServiceProviderTest>(), equalTo(this))
        assertThat(serviceProvider.getRequiredService(SERVICE_NAME), equalTo(this))

        val copiedServiceProvider = serviceProvider.copy()
        assertThat(copiedServiceProvider.getRequiredService<SimpleServiceProviderTest>(), equalTo(this))
        assertThat(copiedServiceProvider.getRequiredService(SERVICE_NAME), equalTo(this))
    }
}
