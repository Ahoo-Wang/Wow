package me.ahoo.wow.spring

import me.ahoo.wow.infra.Decorator.Companion.getOriginalDelegate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory

class SpringServiceProviderTest {

    @Test
    fun register() {
        val beanFactory = DefaultListableBeanFactory()
        val serviceProvider = SpringServiceProvider(beanFactory)
        assertThat(serviceProvider.getService(SpringServiceProviderTest::class.java), nullValue())
        serviceProvider.register(SpringServiceProviderTest::class.java, this)
        assertThat(serviceProvider.getService(SpringServiceProviderTest::class.java), equalTo(this))
        assertThat(serviceProvider.getOriginalDelegate(), equalTo(beanFactory))
    }
}
