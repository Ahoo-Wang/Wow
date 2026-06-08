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

import java.lang.invoke.WrongMethodTypeException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;

final class FunctionInvocationSupport {
    static final Object[] EMPTY_ARGS = new Object[0];

    private FunctionInvocationSupport() {
    }

    static Object[] actualArgs(Object[] args) {
        return args == null ? EMPTY_ARGS : args;
    }

    static Object receiver(Object[] actualArgs) {
        if (actualArgs.length == 0) {
            throw new IllegalArgumentException("Instance function invocation requires receiver as the first argument.");
        }
        return actualArgs[0];
    }

    static Object[] argumentsWithoutReceiver(Object[] actualArgs) {
        return actualArgs.length == 1
            ? EMPTY_ARGS
            : Arrays.copyOfRange(actualArgs, 1, actualArgs.length);
    }

    static Object invoke(InstanceFunctionInvoker invoker, Object[] args)
            throws Throwable {
        Object[] actualArgs = actualArgs(args);
        Object receiver = receiver(actualArgs);
        return switch (actualArgs.length) {
            case 1 -> invoker.invoke0(receiver);
            case 2 -> invoker.invoke1(receiver, actualArgs[1]);
            case 3 -> invoker.invoke2(receiver, actualArgs[1], actualArgs[2]);
            case 4 -> invoker.invoke3(receiver, actualArgs[1], actualArgs[2], actualArgs[3]);
            case 5 -> invoker.invoke4(receiver, actualArgs[1], actualArgs[2], actualArgs[3], actualArgs[4]);
            case 6 -> invoker.invoke5(receiver, actualArgs[1], actualArgs[2], actualArgs[3], actualArgs[4],
                actualArgs[5]);
            case 7 -> invoker.invoke6(receiver, actualArgs[1], actualArgs[2], actualArgs[3], actualArgs[4],
                actualArgs[5], actualArgs[6]);
            case 8 -> invoker.invoke7(receiver, actualArgs[1], actualArgs[2], actualArgs[3], actualArgs[4],
                actualArgs[5], actualArgs[6], actualArgs[7]);
            case 9 -> invoker.invoke8(receiver, actualArgs[1], actualArgs[2], actualArgs[3], actualArgs[4],
                actualArgs[5], actualArgs[6], actualArgs[7], actualArgs[8]);
            case 10 -> invoker.invoke9(receiver, actualArgs[1], actualArgs[2], actualArgs[3], actualArgs[4],
                actualArgs[5], actualArgs[6], actualArgs[7], actualArgs[8], actualArgs[9]);
            default -> invoker.invoke(receiver, argumentsWithoutReceiver(actualArgs));
        };
    }

    static Object invoke(InstanceFunctionInvoker invoker, Object receiver, Object[] args)
            throws Throwable {
        Object[] actualArgs = actualArgs(args);
        return switch (actualArgs.length) {
            case 0 -> invoker.invoke0(receiver);
            case 1 -> invoker.invoke1(receiver, actualArgs[0]);
            case 2 -> invoker.invoke2(receiver, actualArgs[0], actualArgs[1]);
            case 3 -> invoker.invoke3(receiver, actualArgs[0], actualArgs[1], actualArgs[2]);
            case 4 -> invoker.invoke4(receiver, actualArgs[0], actualArgs[1], actualArgs[2], actualArgs[3]);
            case 5 -> invoker.invoke5(receiver, actualArgs[0], actualArgs[1], actualArgs[2], actualArgs[3],
                    actualArgs[4]);
            case 6 -> invoker.invoke6(receiver, actualArgs[0], actualArgs[1], actualArgs[2], actualArgs[3],
                    actualArgs[4], actualArgs[5]);
            case 7 -> invoker.invoke7(receiver, actualArgs[0], actualArgs[1], actualArgs[2], actualArgs[3],
                    actualArgs[4], actualArgs[5], actualArgs[6]);
            case 8 -> invoker.invoke8(receiver, actualArgs[0], actualArgs[1], actualArgs[2], actualArgs[3],
                    actualArgs[4], actualArgs[5], actualArgs[6], actualArgs[7]);
            case 9 -> invoker.invoke9(receiver, actualArgs[0], actualArgs[1], actualArgs[2], actualArgs[3],
                    actualArgs[4], actualArgs[5], actualArgs[6], actualArgs[7], actualArgs[8]);
            default -> invoker.invoke(receiver, actualArgs);
        };
    }

    static Object invoke(ReceiverlessFunctionInvoker invoker, Object[] args) throws Throwable {
        Object[] actualArgs = actualArgs(args);
        return switch (actualArgs.length) {
            case 0 -> invoker.invoke0();
            case 1 -> invoker.invoke1(actualArgs[0]);
            case 2 -> invoker.invoke2(actualArgs[0], actualArgs[1]);
            case 3 -> invoker.invoke3(actualArgs[0], actualArgs[1], actualArgs[2]);
            case 4 -> invoker.invoke4(actualArgs[0], actualArgs[1], actualArgs[2], actualArgs[3]);
            case 5 -> invoker.invoke5(actualArgs[0], actualArgs[1], actualArgs[2], actualArgs[3], actualArgs[4]);
            case 6 -> invoker.invoke6(actualArgs[0], actualArgs[1], actualArgs[2], actualArgs[3], actualArgs[4],
                    actualArgs[5]);
            case 7 -> invoker.invoke7(actualArgs[0], actualArgs[1], actualArgs[2], actualArgs[3], actualArgs[4],
                    actualArgs[5], actualArgs[6]);
            case 8 -> invoker.invoke8(actualArgs[0], actualArgs[1], actualArgs[2], actualArgs[3], actualArgs[4],
                    actualArgs[5], actualArgs[6], actualArgs[7]);
            case 9 -> invoker.invoke9(actualArgs[0], actualArgs[1], actualArgs[2], actualArgs[3], actualArgs[4],
                    actualArgs[5], actualArgs[6], actualArgs[7], actualArgs[8]);
            default -> invoker.invoke(actualArgs);
        };
    }

    static Throwable normalizeInvocationException(Throwable error, Method method, boolean staticMethod,
                                                  Object receiver, Object... args) {
        if (shouldNormalize(error) && !isValidInvocation(method, staticMethod, receiver, args)) {
            return new IllegalArgumentException(error.getMessage(), error);
        }
        return error;
    }

    static Throwable normalizeInvocationException(Throwable error, Constructor<?> constructor, Object... args) {
        if (shouldNormalize(error) && !isValidInvocation(constructor.getParameterTypes(), args)) {
            return new IllegalArgumentException(error.getMessage(), error);
        }
        return error;
    }

    private static boolean shouldNormalize(Throwable error) {
        return error instanceof WrongMethodTypeException
                || error instanceof ClassCastException
                || error instanceof NullPointerException;
    }

    private static boolean isValidInvocation(Method method, boolean staticMethod, Object receiver, Object[] args) {
        if (!staticMethod && (receiver == null || !method.getDeclaringClass().isInstance(receiver))) {
            return false;
        }
        return isValidInvocation(method.getParameterTypes(), args);
    }

    private static boolean isValidInvocation(Class<?>[] parameterTypes, Object[] args) {
        Object[] actualArgs = actualArgs(args);
        if (actualArgs.length != parameterTypes.length) {
            return false;
        }
        for (int i = 0; i < parameterTypes.length; i++) {
            if (!isMethodInvocationConvertible(actualArgs[i], parameterTypes[i])) {
                return false;
            }
        }
        return true;
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
