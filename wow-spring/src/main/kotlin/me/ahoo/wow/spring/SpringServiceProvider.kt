/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.spring

import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.ioc.ServiceProvider
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.core.ResolvableType
import kotlin.reflect.KType
import kotlin.reflect.jvm.javaType

class SpringServiceProvider(override val delegate: ConfigurableBeanFactory) :
    ServiceProvider,
    Decorator<ConfigurableBeanFactory> {

    override val serviceNames: Set<String>
        get() = delegate.singletonNames.toSet()

    override fun register(service: Any, serviceName: String, serviceType: KType) {
        delegate.registerSingleton(serviceName, service)
    }

    override fun <S : Any> getService(serviceType: KType): S? {
        val resolvableType = ResolvableType.forType(serviceType.javaType)
        return delegate.getBeanProvider<S>(resolvableType).getIfAvailable { null }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <S : Any> getService(serviceName: String): S? {
        return delegate.getBean(serviceName) as S?
    }

    override fun copy(): ServiceProvider {
        val newBeanFactory = DefaultListableBeanFactory()
        newBeanFactory.copyConfigurationFrom(delegate)
        delegate.copyConfigurationFrom(delegate)
        delegate.singletonNames.forEach {
            val bean = delegate.getBean(it)
            newBeanFactory.registerSingleton(it, bean)
        }
        return SpringServiceProvider(newBeanFactory)
    }
}
