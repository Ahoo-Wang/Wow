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

import me.ahoo.wow.kernel.Dependencies
import org.springframework.beans.factory.ListableBeanFactory

/**
 * The parameters of the generated model factories from Spring's [beanFactory]: a dependency
 * named by a qualifier (`@Name("…")` on the parameter) is the bean of that name, when it is of the type; one without
 * is the only bean of its type, or its primary bean when there are several. Several without a primary are ambiguous:
 * the parameter needs a qualifier.
 */
internal class BeanFactoryDependencies(private val beanFactory: ListableBeanFactory) : Dependencies {
    override fun find(type: Class<*>, qualifier: String?): Any? {
        if (qualifier != null) {
            return if (beanFactory.containsBean(qualifier) && beanFactory.isTypeMatch(qualifier, type)) {
                beanFactory.getBean(qualifier, type)
            } else {
                null
            }
        }
        val provider = beanFactory.getBeanProvider(type)
        val names = beanFactory.getBeanNamesForType(type)
        return when (names.size) {
            0 -> null
            1 -> beanFactory.getBean(names.single(), type)
            else -> checkNotNull(provider.getIfUnique()) {
                "${names.size} beans are ${type.name} (${names.joinToString()}) and none is primary: " +
                    "qualify the one the models take with @Name."
            }
        }
    }
}
