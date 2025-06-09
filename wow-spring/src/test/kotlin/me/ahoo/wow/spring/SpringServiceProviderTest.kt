package me.ahoo.wow.spring

import me.ahoo.test.asserts.assert
import me.ahoo.wow.infra.Decorator.Companion.getOriginalDelegate
import me.ahoo.wow.ioc.getRequiredService
import me.ahoo.wow.ioc.getService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import kotlin.reflect.typeOf

class SpringServiceProviderTest {

    @Test
    fun register() {
        val beanFactory = DefaultListableBeanFactory()
        val serviceProvider = SpringServiceProvider(beanFactory)
        serviceProvider.getService<SpringServiceProviderTest>().assert().isNull()
        serviceProvider.register(typeOf<SpringServiceProviderTest>(), this)
        serviceProvider.getRequiredService<SpringServiceProviderTest>().assert().isSameAs(this)
        serviceProvider.getOriginalDelegate().assert().isSameAs(beanFactory)
    }
}
