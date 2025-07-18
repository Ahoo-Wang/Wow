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
import me.ahoo.wow.infra.accessor.method.FastInvoke
import me.ahoo.wow.infra.prepare.PrepareKey
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

class PrepareKeyInvocationHandler(
    private val metadata: PrepareKeyMetadata<*>,
    override val delegate: PrepareKey<*>
) : Decorator<PrepareKey<*>>, InvocationHandler {
    companion object {
        val EMPTY_ARGS = emptyArray<Any>()
    }

    private val declaredDefaultMethods by lazy {
        metadata.proxyInterface.java.declaredMethods.filter { it.isDefault }
    }

    override fun invoke(
        proxy: Any,
        method: Method,
        args: Array<out Any>?
    ): Any? {
        val methodArgs = args ?: EMPTY_ARGS
        if (method.isDefault && declaredDefaultMethods.contains(method)) {
            return InvocationHandler.invokeDefault(proxy, method, *methodArgs)
        }
        return FastInvoke.safeInvoke<Any?>(method, delegate, methodArgs)
    }
}
