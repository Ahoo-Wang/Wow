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

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

final class ReflectionConstructorFunctionInvoker implements ConstructorFunctionInvoker {
    private final Constructor<?> constructor;
    private final int parameterCount;

    ReflectionConstructorFunctionInvoker(Constructor<?> constructor) {
        this.constructor = constructor;
        this.parameterCount = constructor.getParameterCount();
    }

    @Override
    public int parameterCount() {
        return parameterCount;
    }

    @Override
    public Object invoke(Object[] args) throws Throwable {
        try {
            return constructor.newInstance(args);
        } catch (InvocationTargetException targetException) {
            throw targetException.getTargetException();
        }
    }
}
