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

package me.ahoo.wow.infra.accessor.method;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class MethodInvokerFactory {
    private MethodInvokerFactory() {
    }

    public static MethodInvoker create(Method method) {
        try {
            MethodHandle methodHandle = MethodHandles.lookup().unreflect(method).asFixedArity();
            MethodHandler handler = Modifier.isStatic(method.getModifiers())
                ? new StaticMethodInvoker(method, methodHandle)
                : new InstanceMethodInvoker(method, methodHandle);
            return new DefaultMethodInvoker(handler);
        } catch (IllegalAccessException | RuntimeException ignored) {
            return new DefaultMethodInvoker(new ReflectionMethodInvoker(method));
        }
    }
}
