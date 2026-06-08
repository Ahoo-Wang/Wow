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

package me.ahoo.wow.infra.accessor.constructor;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

public final class ConstructorInvokerFactory {
    private ConstructorInvokerFactory() {
    }

    public static <T> ConstructorInvoker<T> create(Constructor<T> constructor) {
        try {
            return new MethodHandleConstructorInvoker<>(constructor);
        } catch (IllegalAccessException | RuntimeException ignored) {
            return new ReflectionConstructorInvoker<>(constructor);
        }
    }

    private static final class MethodHandleConstructorInvoker<T> implements ConstructorInvoker<T> {
        private final MethodHandle handle;
        private final Class<?>[] parameterTypes;
        private final int parameterCount;

        private MethodHandleConstructorInvoker(Constructor<T> constructor) throws IllegalAccessException {
            this.handle = MethodHandles.lookup().unreflectConstructor(constructor).asFixedArity();
            this.parameterTypes = constructor.getParameterTypes();
            this.parameterCount = constructor.getParameterCount();
        }

        @Override
        @SuppressWarnings({"AvoidObjectArrays", "unchecked"})
        public T newInstance(Object[] args) throws Throwable {
            Object[] actualArgs = args == null ? new Object[0] : args;
            validateParameterTypes(actualArgs);
            if (actualArgs.length == 0) {
                return newInstance0();
            }
            if (actualArgs.length == 1) {
                return newInstance1(actualArgs[0]);
            }
            if (actualArgs.length == 2) {
                return newInstance2(actualArgs[0], actualArgs[1]);
            }
            return (T) handle.invokeWithArguments(actualArgs);
        }

        @Override
        @SuppressWarnings("unchecked")
        public T newInstance0() throws Throwable {
            validateParameterTypes();
            return (T) handle.invoke();
        }

        @Override
        @SuppressWarnings("unchecked")
        public T newInstance1(Object arg) throws Throwable {
            validateParameterTypes(arg);
            return (T) handle.invoke(arg);
        }

        @Override
        @SuppressWarnings("unchecked")
        public T newInstance2(Object arg1, Object arg2) throws Throwable {
            validateParameterTypes(arg1, arg2);
            return (T) handle.invoke(arg1, arg2);
        }

        private void validateParameterTypes(Object... args) {
            if (args.length != parameterCount) {
                throw new IllegalArgumentException("wrong number of arguments");
            }
            for (int i = 0; i < parameterCount; i++) {
                if (!isMethodInvocationConvertible(args[i], parameterTypes[i])) {
                    throw new IllegalArgumentException("argument type mismatch");
                }
            }
        }
    }

    private static final class ReflectionConstructorInvoker<T> implements ConstructorInvoker<T> {
        private final Constructor<T> constructor;

        private ReflectionConstructorInvoker(Constructor<T> constructor) {
            this.constructor = constructor;
        }

        @Override
        public T newInstance(Object[] args) throws Throwable {
            try {
                return constructor.newInstance(args);
            } catch (InvocationTargetException targetException) {
                throw targetException.getTargetException();
            }
        }

        @Override
        public T newInstance0() throws Throwable {
            try {
                return constructor.newInstance();
            } catch (InvocationTargetException targetException) {
                throw targetException.getTargetException();
            }
        }

        @Override
        public T newInstance1(Object arg) throws Throwable {
            try {
                return constructor.newInstance(arg);
            } catch (InvocationTargetException targetException) {
                throw targetException.getTargetException();
            }
        }

        @Override
        public T newInstance2(Object arg1, Object arg2) throws Throwable {
            try {
                return constructor.newInstance(arg1, arg2);
            } catch (InvocationTargetException targetException) {
                throw targetException.getTargetException();
            }
        }
    }

    private static boolean isMethodInvocationConvertible(Object arg, Class<?> parameterType) {
        if (arg == null) {
            return !parameterType.isPrimitive();
        }
        if (!parameterType.isPrimitive()) {
            return parameterType.isInstance(arg);
        }
        return isPrimitiveInvocationConvertible(arg.getClass(), parameterType);
    }

    private static boolean isPrimitiveInvocationConvertible(Class<?> argType, Class<?> parameterType) {
        if (parameterType == boolean.class) {
            return argType == Boolean.class;
        }
        if (parameterType == byte.class) {
            return argType == Byte.class;
        }
        if (parameterType == char.class) {
            return argType == Character.class;
        }
        if (parameterType == short.class) {
            return argType == Short.class || argType == Byte.class;
        }
        if (parameterType == int.class) {
            return argType == Integer.class || argType == Short.class || argType == Byte.class
                || argType == Character.class;
        }
        if (parameterType == long.class) {
            return argType == Long.class || argType == Integer.class || argType == Short.class
                || argType == Byte.class || argType == Character.class;
        }
        if (parameterType == float.class) {
            return argType == Float.class || argType == Long.class || argType == Integer.class
                || argType == Short.class || argType == Byte.class || argType == Character.class;
        }
        if (parameterType == double.class) {
            return argType == Double.class || argType == Float.class || argType == Long.class
                || argType == Integer.class || argType == Short.class || argType == Byte.class
                || argType == Character.class;
        }
        return false;
    }
}
