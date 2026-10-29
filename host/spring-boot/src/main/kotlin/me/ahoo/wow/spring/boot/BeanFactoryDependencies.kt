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

import me.ahoo.wow.kernel.AmbiguousDependencyException
import me.ahoo.wow.kernel.Dependencies
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.core.ResolvableType
import java.lang.reflect.Type

/**
 * The parameters of the generated model factories from Spring's [beanFactory]: a dependency
 * named by a qualifier (`@Name("…")` on the parameter) is the bean of that name, when it is of the type; one without
 * is the only bean of its type, or its primary bean when there are several. Several without a primary are ambiguous:
 * the parameter needs a qualifier. A parameterized type is matched by every type argument, as Spring matches an
 * injection point: `PublishedStateCache<OrderStateView>` is not `PublishedStateCache<PaymentStateView>`.
 */
internal class BeanFactoryDependencies(private val beanFactory: ListableBeanFactory) : Dependencies {
    override fun find(type: Class<*>, qualifier: String?): Any? = find(ResolvableType.forClass(type), type, qualifier)

    override fun find(type: Type, qualifier: String?): Any? = find(ResolvableType.forType(type), type, qualifier)

    private fun find(resolvable: ResolvableType, type: Type, qualifier: String?): Any? {
        if (qualifier != null) {
            return if (beanFactory.containsBean(qualifier) && beanFactory.isTypeMatch(qualifier, resolvable)) {
                beanFactory.getBean(qualifier)
            } else {
                null
            }
        }
        val names = beanFactory.getBeanNamesForType(resolvable)
        return when (names.size) {
            0 -> null
            1 -> beanFactory.getBean(names.single())
            else -> beanFactory.getBeanProvider<Any>(resolvable).getIfUnique()
                ?: throw AmbiguousDependencyException(type, names.toList())
        }
    }
}
