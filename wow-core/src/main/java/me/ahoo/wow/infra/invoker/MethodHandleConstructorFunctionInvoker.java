/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com>].
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

package me.ahoo.wow.infra.invoker;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;

final class MethodHandleConstructorFunctionInvoker extends AbstractMethodHandleReceiverlessFunctionInvoker
        implements ConstructorFunctionInvoker {
    private final Constructor<?> constructor;

    MethodHandleConstructorFunctionInvoker(Constructor<?> constructor, MethodHandle handle) {
        super(handle, constructor.getParameterCount());
        this.constructor = constructor;
    }

    @Override
    protected Throwable normalize(Throwable error, Object... args) {
        return FunctionInvocationSupport.normalizeInvocationException(error, constructor, args);
    }
}
