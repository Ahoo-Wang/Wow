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

package me.ahoo.wow.spring.prepare

import me.ahoo.wow.infra.prepare.PrepareKey
import me.ahoo.wow.infra.prepare.proxy.PrepareKeyMetadata
import me.ahoo.wow.infra.prepare.proxy.PrepareKeyProxyFactory
import org.springframework.beans.factory.FactoryBean
import org.springframework.beans.factory.getBean
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware

class PrepareKeyFactoryBean<P : PrepareKey<*>>(private val metadata: PrepareKeyMetadata<P>) :
    FactoryBean<P>,
    ApplicationContextAware {
    private lateinit var appContext: ApplicationContext
    override fun setApplicationContext(applicationContext: ApplicationContext) {
        this.appContext = applicationContext
    }

    override fun getObject(): P? {
        val prepareKeyProxyFactory = appContext.getBean<PrepareKeyProxyFactory>()
        return prepareKeyProxyFactory.create(metadata)
    }

    override fun getObjectType(): Class<*> {
        return metadata.proxyInterface.java
    }
}
