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

package me.ahoo.wow.datasetregistry.server

import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.caller.InternalCallerResolver
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory

/**
 * Makes the public port's [CallerResolver] the one the host injects (roadmap P.15): with CoSec's request and
 * security-context parsers, the edge defines CoSec's [InternalCallerResolver] beside CoSec's public resolver, and the
 * internal one is a [CallerResolver] too, so the host's injection of the public port's resolver found two. Each bean
 * of [CallerResolver] that is not an [InternalCallerResolver] becomes primary.
 *
 * A stopgap until the host tells the two apart itself; remove it then.
 */
class PublicCallersFirst : BeanFactoryPostProcessor {
    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
        val internal = beanFactory.getBeanNamesForType(InternalCallerResolver::class.java, true, false).toSet()
        val callers = beanFactory.getBeanNamesForType(CallerResolver::class.java, true, false)
        val public = callers.filterNot { it in internal }
        if (internal.isEmpty() || public.size != 1) {
            return
        }
        beanFactory.getBeanDefinition(public.single()).isPrimary = true
    }
}
