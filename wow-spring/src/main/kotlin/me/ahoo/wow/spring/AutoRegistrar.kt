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

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.ApplicationContext

/**
 * Must complete before the Wow runtime readiness barrier opens.
 * @see WowRuntimeLifecycle
 */
abstract class AutoRegistrar<CM : Annotation>(
    private val componentType: Class<CM>,
    private val applicationContext: ApplicationContext
) : SmartInitializingSingleton {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    final override fun afterSingletonsInstantiated() {
        log.info {
            "Register component:${componentType.simpleName}."
        }
        applicationContext.getBeansWithAnnotation(componentType).forEach { (_, component) ->
            log.debug {
                "Registering Component [$component]."
            }
            register(component)
        }
    }

    abstract fun register(component: Any)
}
