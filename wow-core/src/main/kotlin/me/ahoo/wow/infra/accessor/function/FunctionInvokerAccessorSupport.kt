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
package me.ahoo.wow.infra.accessor.function

import me.ahoo.wow.infra.invoker.FunctionInvoker
import me.ahoo.wow.infra.invoker.InstanceFunctionInvoker
import me.ahoo.wow.infra.invoker.InvocationArguments
import me.ahoo.wow.infra.invoker.StaticFunctionInvoker
import kotlin.reflect.KFunction
import kotlin.reflect.full.extensionReceiverParameter
import kotlin.reflect.full.instanceParameter

internal val KFunction<*>.isStaticExtensionFunction: Boolean
    get() = instanceParameter == null && extensionReceiverParameter != null

internal fun FunctionInvoker.invokeFunction(
    function: KFunction<*>,
    target: Any?,
    args: Array<Any?>
): Any? =
    when (this) {
        is InstanceFunctionInvoker -> invoke(target, args)
        is StaticFunctionInvoker -> {
            if (function.isStaticExtensionFunction) {
                invoke(InvocationArguments.prependReceiver(target, args))
            } else {
                invoke(args)
            }
        }

        else -> error("Unsupported function invoker: ${javaClass.name}")
    }

internal fun FunctionInvoker.invokeFunction1(
    function: KFunction<*>,
    target: Any?,
    arg: Any?
): Any? =
    when (this) {
        is InstanceFunctionInvoker -> invoke1(target, arg)
        is StaticFunctionInvoker -> {
            if (function.isStaticExtensionFunction) {
                invoke2(target, arg)
            } else {
                invoke1(arg)
            }
        }

        else -> error("Unsupported function invoker: ${javaClass.name}")
    }
