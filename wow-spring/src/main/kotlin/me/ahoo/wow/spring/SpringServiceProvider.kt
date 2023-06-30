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
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.config.ConfigurableBeanFactory

class SpringServiceProvider(override val delegate: ConfigurableBeanFactory) :
    ServiceProvider,
    Decorator<ConfigurableBeanFactory> {

    override fun <S : Any> register(serviceType: Class<S>, service: S) {
        register(service)
    }

    override fun <S : Any> register(serviceName: String, service: S) {
        delegate.registerSingleton(serviceName, service)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <S : Any> getService(serviceName: String): S? {
        return delegate.getBean(serviceName) as S?
    }

    override fun <S : Any> getService(serviceType: Class<S>): S? {
        return try {
            delegate.getBean(serviceType)
        } catch (e: NoSuchBeanDefinitionException) {
            null
        }
    }

}
