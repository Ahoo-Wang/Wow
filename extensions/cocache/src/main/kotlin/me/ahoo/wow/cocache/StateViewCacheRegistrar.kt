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

package me.ahoo.wow.cocache

import me.ahoo.wow.cocache.api.StateViewCache
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.core.ResolvableType

/**
 * Registers a `StateViewCache<V>` bean for each state view the peer contracts on the class path name
 * ([ContractViews]), so a service injects `StateViewCache<OrderStateView>` with nothing declared: one lazy bean per
 * view, typed by its generics, named `wow.cocache.<context>.<aggregate>`, which the service's [StateViewCaches] gives.
 */
class StateViewCacheRegistrar : BeanDefinitionRegistryPostProcessor {
    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
        val beans = registry as? ConfigurableListableBeanFactory ?: return
        val classLoader = beans.beanClassLoader ?: javaClass.classLoader
        ContractViews.onClassPath(classLoader).forEach { (type, view) ->
            val name = beanNameOf(type.toString())
            if (registry.containsBeanDefinition(name)) return@forEach
            val definition = RootBeanDefinition(StateViewCache::class.java).apply {
                setTargetType(ResolvableType.forClassWithGenerics(StateViewCache::class.java, view))
                isLazyInit = true
                setInstanceSupplier { beans.getBean(StateViewCaches::class.java).of(view) }
            }
            registry.registerBeanDefinition(name, definition)
        }
    }

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) = Unit

    companion object {
        /** The bean of the cache of the aggregate type [type], `<context>.<aggregate>`. */
        fun beanNameOf(type: String): String = "wow.cocache.$type"
    }
}
