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

package me.ahoo.wow.infra.invoker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * A function that fails with its own exception fails the invocation with that exception, unchanged, whatever its
 * arity: only an invocation that does not fit the method (wrong receiver, arguments or count) becomes an
 * {@link IllegalArgumentException}.
 */
class FunctionInvokerErrorContractTest {
    private static final int MAX_FIXED_ARITY = 9;

    @Test
    void staticFunctionErrorsPropagateUnchangedForEveryArity() throws Throwable {
        for (int arity = 0; arity <= MAX_FIXED_ARITY; arity++) {
            Method method = Failing.class.getDeclaredMethod("staticFail" + arity, parameterTypes(arity));
            method.trySetAccessible();
            StaticFunctionInvoker invoker = (StaticFunctionInvoker) FunctionInvokerFactory.create(method);
            Object[] args = arguments(arity);
            String expected = "static " + arity;

            assertThatThrownBy(() -> invokeFixed(invoker, args))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(expected);
            assertThatThrownBy(() -> invoker.invoke(args))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(expected);
        }
    }

    @Test
    void instanceFunctionErrorsPropagateUnchangedForEveryArity() throws Throwable {
        Failing receiver = new Failing();
        for (int arity = 0; arity <= MAX_FIXED_ARITY; arity++) {
            Method method = Failing.class.getDeclaredMethod("fail" + arity, parameterTypes(arity));
            method.trySetAccessible();
            InstanceFunctionInvoker invoker = (InstanceFunctionInvoker) FunctionInvokerFactory.create(method);
            Object[] args = arguments(arity);
            String expected = "instance " + arity;

            assertThatThrownBy(() -> invokeFixed(invoker, receiver, args))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(expected);
            assertThatThrownBy(() -> invoker.invoke(receiver, args))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(expected);
        }
    }

    @Test
    void aWrongArgumentTypeIsAnIllegalArgumentForEveryArity() throws Throwable {
        Failing receiver = new Failing();
        for (int arity = 1; arity <= MAX_FIXED_ARITY; arity++) {
            Object[] args = arguments(arity);
            args[arity - 1] = 1;
            Method staticMethod = Failing.class.getDeclaredMethod("staticFail" + arity, parameterTypes(arity));
            staticMethod.trySetAccessible();
            StaticFunctionInvoker staticInvoker = (StaticFunctionInvoker) FunctionInvokerFactory.create(staticMethod);
            Method method = Failing.class.getDeclaredMethod("fail" + arity, parameterTypes(arity));
            method.trySetAccessible();
            InstanceFunctionInvoker invoker = (InstanceFunctionInvoker) FunctionInvokerFactory.create(method);

            assertThatThrownBy(() -> invokeFixed(staticInvoker, args)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> invokeFixed(invoker, receiver, args)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(MAX_FIXED_ARITY).isEqualTo(9);
    }

    private static Object invokeFixed(StaticFunctionInvoker invoker, Object[] a) throws Throwable {
        return switch (a.length) {
            case 0 -> invoker.invoke0();
            case 1 -> invoker.invoke1(a[0]);
            case 2 -> invoker.invoke2(a[0], a[1]);
            case 3 -> invoker.invoke3(a[0], a[1], a[2]);
            case 4 -> invoker.invoke4(a[0], a[1], a[2], a[3]);
            case 5 -> invoker.invoke5(a[0], a[1], a[2], a[3], a[4]);
            case 6 -> invoker.invoke6(a[0], a[1], a[2], a[3], a[4], a[5]);
            case 7 -> invoker.invoke7(a[0], a[1], a[2], a[3], a[4], a[5], a[6]);
            case 8 -> invoker.invoke8(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7]);
            default -> invoker.invoke9(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8]);
        };
    }

    private static Object invokeFixed(InstanceFunctionInvoker invoker, Object r, Object[] a) throws Throwable {
        return switch (a.length) {
            case 0 -> invoker.invoke0(r);
            case 1 -> invoker.invoke1(r, a[0]);
            case 2 -> invoker.invoke2(r, a[0], a[1]);
            case 3 -> invoker.invoke3(r, a[0], a[1], a[2]);
            case 4 -> invoker.invoke4(r, a[0], a[1], a[2], a[3]);
            case 5 -> invoker.invoke5(r, a[0], a[1], a[2], a[3], a[4]);
            case 6 -> invoker.invoke6(r, a[0], a[1], a[2], a[3], a[4], a[5]);
            case 7 -> invoker.invoke7(r, a[0], a[1], a[2], a[3], a[4], a[5], a[6]);
            case 8 -> invoker.invoke8(r, a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7]);
            default -> invoker.invoke9(r, a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8]);
        };
    }

    private static Class<?>[] parameterTypes(int arity) {
        Class<?>[] parameterTypes = new Class<?>[arity];
        Arrays.fill(parameterTypes, String.class);
        return parameterTypes;
    }

    private static Object[] arguments(int arity) {
        Object[] arguments = new Object[arity];
        Arrays.fill(arguments, "arg");
        return arguments;
    }

    @SuppressWarnings({"unused", "checkstyle:ParameterNumber"})
    static class Failing {
        private static Object staticFail0() {
            throw new IllegalStateException("static 0");
        }

        private static Object staticFail1(String a1) {
            throw new IllegalStateException("static 1");
        }

        private static Object staticFail2(String a1, String a2) {
            throw new IllegalStateException("static 2");
        }

        private static Object staticFail3(String a1, String a2, String a3) {
            throw new IllegalStateException("static 3");
        }

        private static Object staticFail4(String a1, String a2, String a3, String a4) {
            throw new IllegalStateException("static 4");
        }

        private static Object staticFail5(String a1, String a2, String a3, String a4, String a5) {
            throw new IllegalStateException("static 5");
        }

        private static Object staticFail6(String a1, String a2, String a3, String a4, String a5, String a6) {
            throw new IllegalStateException("static 6");
        }

        private static Object staticFail7(String a1, String a2, String a3, String a4, String a5, String a6,
                                          String a7) {
            throw new IllegalStateException("static 7");
        }

        private static Object staticFail8(String a1, String a2, String a3, String a4, String a5, String a6,
                                          String a7, String a8) {
            throw new IllegalStateException("static 8");
        }

        private static Object staticFail9(String a1, String a2, String a3, String a4, String a5, String a6,
                                          String a7, String a8, String a9) {
            throw new IllegalStateException("static 9");
        }

        private Object fail0() {
            throw new IllegalStateException("instance 0");
        }

        private Object fail1(String a1) {
            throw new IllegalStateException("instance 1");
        }

        private Object fail2(String a1, String a2) {
            throw new IllegalStateException("instance 2");
        }

        private Object fail3(String a1, String a2, String a3) {
            throw new IllegalStateException("instance 3");
        }

        private Object fail4(String a1, String a2, String a3, String a4) {
            throw new IllegalStateException("instance 4");
        }

        private Object fail5(String a1, String a2, String a3, String a4, String a5) {
            throw new IllegalStateException("instance 5");
        }

        private Object fail6(String a1, String a2, String a3, String a4, String a5, String a6) {
            throw new IllegalStateException("instance 6");
        }

        private Object fail7(String a1, String a2, String a3, String a4, String a5, String a6, String a7) {
            throw new IllegalStateException("instance 7");
        }

        private Object fail8(String a1, String a2, String a3, String a4, String a5, String a6, String a7,
                             String a8) {
            throw new IllegalStateException("instance 8");
        }

        private Object fail9(String a1, String a2, String a3, String a4, String a5, String a6, String a7,
                             String a8, String a9) {
            throw new IllegalStateException("instance 9");
        }
    }
}
