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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class MethodInvokerFactory {
    private MethodInvokerFactory() {
    }

    public static MethodInvoker create(Method method) {
        try {
            return new MethodHandleInvoker(method);
        } catch (IllegalAccessException | RuntimeException ignored) {
            return new ReflectionInvoker(method);
        }
    }

    private static final class MethodHandleInvoker implements MethodInvoker {
        private final Method method;
        private final MethodHandle handle;
        private final boolean staticMethod;
        private final int parameterCount;

        private MethodHandleInvoker(Method method) throws IllegalAccessException {
            this.method = method;
            this.handle = MethodHandles.lookup().unreflect(method).asFixedArity();
            this.staticMethod = Modifier.isStatic(method.getModifiers());
            this.parameterCount = method.getParameterCount();
        }

        @Override
        @SuppressWarnings("AvoidObjectArrays")
        public Object invoke(Object target, Object[] args) throws Throwable {
            Object[] actualArgs = args == null ? new Object[0] : args;
            if (staticMethod) {
                return invokeStatic(actualArgs);
            }
            return invokeInstance(target, actualArgs);
        }

        @Override
        public Object invokeSingle(Object target, Object arg) throws Throwable {
            if (parameterCount != 1) {
                return invoke(target, new Object[]{arg});
            }
            if (staticMethod) {
                return handle.invoke(arg);
            }
            return handle.invoke(target, arg);
        }

        @SuppressWarnings("unused")
        Method method() {
            return method;
        }

        private Object invokeStatic(Object[] args) throws Throwable {
            if (args.length == 0) {
                return handle.invoke();
            }
            if (args.length == 1) {
                return handle.invoke(args[0]);
            }
            if (args.length == 2) {
                return handle.invoke(args[0], args[1]);
            }
            return handle.invokeWithArguments(args);
        }

        private Object invokeInstance(Object target, Object[] args) throws Throwable {
            if (args.length == 0) {
                return handle.invoke(target);
            }
            if (args.length == 1) {
                return handle.invoke(target, args[0]);
            }
            if (args.length == 2) {
                return handle.invoke(target, args[0], args[1]);
            }
            Object[] arguments = new Object[args.length + 1];
            arguments[0] = target;
            System.arraycopy(args, 0, arguments, 1, args.length);
            return handle.invokeWithArguments(arguments);
        }
    }

    private static final class ReflectionInvoker implements MethodInvoker {
        private final Method method;

        private ReflectionInvoker(Method method) {
            this.method = method;
        }

        @Override
        public Object invoke(Object target, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException targetException) {
                throw targetException.getTargetException();
            }
        }

        @Override
        public Object invokeSingle(Object target, Object arg) throws Throwable {
            try {
                return method.invoke(target, arg);
            } catch (InvocationTargetException targetException) {
                throw targetException.getTargetException();
            }
        }
    }
}
