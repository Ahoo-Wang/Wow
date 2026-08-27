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

import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.infra.invoker.FunctionInvokerFactory
import me.ahoo.wow.infra.invoker.InstanceFunctionInvoker
import me.ahoo.wow.infra.prepare.PrepareKey
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Invocation handler for PrepareKey proxy instances.
 * This handler delegates method calls to the underlying PrepareKey implementation,
 * with special handling for default methods in interfaces.
 *
 * @param metadata the metadata for the proxy configuration
 * @property delegate the actual PrepareKey instance to delegate calls to
 *
 * @see DefaultPrepareKeyProxyFactory
 * @see PrepareKey
 */
class PrepareKeyInvocationHandler(
    private val metadata: PrepareKeyMetadata<*>,
    override val delegate: PrepareKey<*>
) : Decorator<PrepareKey<*>>,
    InvocationHandler {
    companion object {
        /** Empty array constant for methods with no arguments. */
        val EMPTY_ARGS = emptyArray<Any>()
    }

    /** Lazily initialized list of default methods declared in the proxy interface. */
    private val declaredDefaultMethods by lazy {
        metadata.proxyInterface.java.declaredMethods
            .filter { it.isDefault }
    }

    private val invokers = ConcurrentHashMap<Method, InstanceFunctionInvoker>()

    /**
     * Handles method invocation on the proxy instance.
     * For default methods, uses the JDK's default method invocation mechanism.
     * For other methods, delegates to the underlying PrepareKey instance using fast invocation.
     *
     * @param proxy the proxy instance
     * @param method the method being invoked
     * @param args the method arguments (null if no arguments)
     * @return the result of the method invocation
     */
    override fun invoke(
        proxy: Any,
        method: Method,
        args: Array<out Any>?
    ): Any? {
        val methodArgs = args ?: EMPTY_ARGS
        if (method.isDefault && declaredDefaultMethods.contains(method)) {
            return InvocationHandler.invokeDefault(proxy, method, *methodArgs)
        }
        @Suppress("UNCHECKED_CAST")
        return invoker(method).invoke(delegate, methodArgs as Array<Any?>)
    }

    private fun invoker(method: Method): InstanceFunctionInvoker {
        return invokers.computeIfAbsent(method) {
            FunctionInvokerFactory.create(it) as InstanceFunctionInvoker
        }
    }
}
