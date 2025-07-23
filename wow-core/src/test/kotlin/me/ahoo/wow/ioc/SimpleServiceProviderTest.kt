package me.ahoo.wow.ioc

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.ioc.SimpleServiceProviderTest.Companion.SERVICE_NAME
import org.junit.jupiter.api.Test
import kotlin.reflect.typeOf

@Name(SERVICE_NAME)
class SimpleServiceProviderTest {
    companion object {
        const val SERVICE_NAME = "serviceProviderTest"
    }

    @Test
    fun getService() {
        val serviceProvider = SimpleServiceProvider()
        serviceProvider.register<SimpleServiceProviderTest>(this)
        serviceProvider.serviceNames.assert().contains(SERVICE_NAME)
        serviceProvider.getRequiredService<SimpleServiceProviderTest>().assert().isSameAs(this)
        serviceProvider.getRequiredService<SimpleServiceProviderTest>(SERVICE_NAME).assert().isSameAs(this)

        assertThrownBy<IllegalArgumentException> {
            serviceProvider.getRequiredService<SimpleServiceProviderTest>("notExist")
        }

        assertThrownBy<IllegalArgumentException> {
            serviceProvider.getRequiredService<SimpleServiceProviderTest>(typeOf<MockService>())
        }

        assertThrownBy<IllegalArgumentException> {
            serviceProvider.getRequiredService<MockService>()
        }

        assertThrownBy<IllegalArgumentException> {
            serviceProvider.getRequiredService(MockService::class.java)
        }

        val copiedServiceProvider = serviceProvider.copy()
        copiedServiceProvider.getRequiredService<SimpleServiceProviderTest>().assert().isSameAs(this)
        copiedServiceProvider.getRequiredService<SimpleServiceProviderTest>(SERVICE_NAME).assert().isSameAs(this)

        val targetServiceProvider = SimpleServiceProvider()
        copiedServiceProvider.copyTo(targetServiceProvider)
        targetServiceProvider.getRequiredService<SimpleServiceProviderTest>().assert().isSameAs(this)
        targetServiceProvider.getRequiredService<SimpleServiceProviderTest>(SERVICE_NAME).assert().isSameAs(this)
    }

    object MockService
}
