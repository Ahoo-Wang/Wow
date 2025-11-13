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

package me.ahoo.wow.infra.prepare.proxy

import me.ahoo.wow.infra.prepare.PrepareKey
import me.ahoo.wow.infra.prepare.PrepareKeyFactory
import java.lang.reflect.Proxy

/**
 * Default implementation of PrepareKeyProxyFactory that creates JDK dynamic proxies.
 * This factory uses Java's Proxy class to create runtime proxy instances that delegate
 * to actual PrepareKey implementations through an invocation handler.
 *
 * @property prepareKeyFactory the factory used to create the underlying PrepareKey instances
 *
 * @see PrepareKeyProxyFactory
 * @see PrepareKeyInvocationHandler
 */
class DefaultPrepareKeyProxyFactory(
    private val prepareKeyFactory: PrepareKeyFactory
) : PrepareKeyProxyFactory {
    /**
     * Creates a proxy instance of the PrepareKey interface using JDK dynamic proxy.
     * The proxy delegates all method calls to the underlying PrepareKey instance
     * created by the prepareKeyFactory, with special handling for default methods.
     *
     * @param P the PrepareKey interface type
     * @param metadata the metadata containing proxy configuration
     * @return a proxy instance implementing the PrepareKey interface
     */
    @Suppress("UNCHECKED_CAST")
    override fun <P : PrepareKey<*>> create(metadata: PrepareKeyMetadata<P>): P {
        val delegate = prepareKeyFactory.create(metadata.name, metadata.valueType.java)
        val invocationHandler = PrepareKeyInvocationHandler(metadata = metadata, delegate = delegate)
        return Proxy.newProxyInstance(
            this.javaClass.classLoader,
            arrayOf(metadata.proxyInterface.java),
            invocationHandler,
        ) as P
    }
}
