package me.ahoo.wow.ioc

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.ioc.SimpleServiceProviderTest.Companion.SERVICE_NAME
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
        serviceProvider.getRequiredService<SimpleServiceProviderTest>().assert().isSameAs(this)
        serviceProvider.getRequiredService<SimpleServiceProviderTest>(SERVICE_NAME).assert().isSameAs(this)

        val copiedServiceProvider = serviceProvider.copy()
        copiedServiceProvider.getRequiredService<SimpleServiceProviderTest>().assert().isSameAs(this)
        copiedServiceProvider.getRequiredService<SimpleServiceProviderTest>(SERVICE_NAME).assert().isSameAs(this)
    }
}
