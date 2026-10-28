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

package me.ahoo.wow.spring.boot

import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.query.cache.QueryCountCache
import me.ahoo.wow.query.dataset.DatasetVisibility
import me.ahoo.wow.query.schema.MaskStrategyResolver
import me.ahoo.wow.spi.call.CommandCaller
import me.ahoo.wow.spi.config.configCheck
import me.ahoo.wow.spi.id.GlobalIdGenerator
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory

/**
 * The seams a node takes exactly one bean of ([SEAMS]): the caller resolver, the global id generator, the mask
 * strategies, the datasets' visibility, the command caller and the count cache. Every other hook is a list the node
 * asks in its beans' order (`@Order`); a seam is not. Several beans of one fail the start before any bean is built,
 * naming the seam and each bean, unless one of them is `@Primary`, the one way to pick among several.
 */
internal class SingleSeams : BeanFactoryPostProcessor {
    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
        SEAMS.forEach { (type, seam) -> check(beanFactory, type, seam) }
    }

    internal companion object {
        /** The single seams, each by its type, with its name in a failure. */
        val SEAMS: Map<Class<*>, String> = linkedMapOf(
            CallerResolver::class.java to "the caller resolver",
            GlobalIdGenerator::class.java to "the global id generator",
            MaskStrategyResolver::class.java to "the mask strategies",
            DatasetVisibility::class.java to "the datasets' visibility",
            CommandCaller::class.java to "the command caller",
            QueryCountCache::class.java to "the count cache",
        )

        /** Fails when [beanFactory] defines several beans of [type], the [seam], none of them `@Primary` alone. */
        fun check(beanFactory: ConfigurableListableBeanFactory, type: Class<*>, seam: String) {
            val names = beanFactory.getBeanNamesForType(type, true, false).toList()
            if (names.size <= 1) return
            val primary = names.filter { beanFactory.containsBeanDefinition(it) && beanFactory.getBeanDefinition(it).isPrimary }
            configCheck(
                primary.size == 1,
                type.name,
                names,
                "Keep one of them, or mark the one the node takes @Primary.",
            ) { "${names.size} beans are $seam (${type.simpleName}): ${names.joinToString()}; the node takes one." }
        }
    }
}
