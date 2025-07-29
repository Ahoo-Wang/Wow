package me.ahoo.wow.spring

import me.ahoo.test.asserts.assert
import me.ahoo.wow.infra.Decorator.Companion.getOriginalDelegate
import me.ahoo.wow.ioc.getRequiredService
import me.ahoo.wow.ioc.getService
import me.ahoo.wow.naming.annotation.toName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import kotlin.reflect.typeOf

class SpringServiceProviderTest {

    @Test
    fun register() {
        val beanFactory = DefaultListableBeanFactory()
        val serviceProvider = SpringServiceProvider(beanFactory)
        serviceProvider.getService<SpringServiceProviderTest>().assert().isNull()
        serviceProvider.register(this, serviceType = typeOf<SpringServiceProviderTest>())
        serviceProvider.serviceNames.assert().contains(SpringServiceProviderTest::class.java.toName())
        serviceProvider.getRequiredService<SpringServiceProviderTest>().assert().isSameAs(this)
        serviceProvider.getOriginalDelegate().assert().isSameAs(beanFactory)

        val copiedServiceProvider = serviceProvider.copy()
        copiedServiceProvider.getRequiredService<SpringServiceProviderTest>().assert().isSameAs(this)
        val targetServiceProvider = SpringServiceProvider(DefaultListableBeanFactory())
        serviceProvider.copyTo(targetServiceProvider)
        targetServiceProvider.getRequiredService<SpringServiceProviderTest>().assert().isSameAs(this)
    }
}
