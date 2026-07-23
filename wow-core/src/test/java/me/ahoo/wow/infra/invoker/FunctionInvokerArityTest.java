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

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

class FunctionInvokerArityTest {

    @Test
    void instanceMethodSupportsZeroThroughTenArguments() throws Throwable {
        ArityTarget target = new ArityTarget();
        for (int arity = 0; arity <= 10; arity++) {
            Method method = ArityTarget.class.getDeclaredMethod("arity" + arity, parameterTypes(arity));
            method.trySetAccessible();
            InstanceFunctionInvoker invoker = (InstanceFunctionInvoker) FunctionInvokerFactory.create(method);

            assertThat(invoker.invoke(target, arguments(arity))).isEqualTo(arity);
        }
    }

    @Test
    void staticMethodSupportsZeroThroughTenArguments() throws Throwable {
        for (int arity = 0; arity <= 10; arity++) {
            Method method = ArityTarget.class.getDeclaredMethod("staticArity" + arity, parameterTypes(arity));
            method.trySetAccessible();
            StaticFunctionInvoker invoker = (StaticFunctionInvoker) FunctionInvokerFactory.create(method);

            assertThat(invoker.invoke(arguments(arity))).isEqualTo(arity);
        }
    }

    @Test
    void constructorSupportsZeroThroughTenArguments() throws Throwable {
        for (int arity = 0; arity <= 10; arity++) {
            Constructor<ArityConstructedTarget> constructor = ArityConstructedTarget.class
                .getDeclaredConstructor(parameterTypes(arity));
            constructor.trySetAccessible();
            ConstructorFunctionInvoker invoker = FunctionInvokerFactory.create(constructor);

            assertThat(((ArityConstructedTarget) invoker.invoke(arguments(arity))).arity).isEqualTo(arity);
        }
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

    @SuppressWarnings("unused")
    static class ArityTarget {
        private int arity0() {
            return 0;
        }

        private int arity1(String arg1) {
            return 1;
        }

        private int arity2(String arg1, String arg2) {
            return 2;
        }

        private int arity3(String arg1, String arg2, String arg3) {
            return 3;
        }

        private int arity4(String arg1, String arg2, String arg3, String arg4) {
            return 4;
        }

        private int arity5(String arg1, String arg2, String arg3, String arg4, String arg5) {
            return 5;
        }

        private int arity6(String arg1, String arg2, String arg3, String arg4, String arg5, String arg6) {
            return 6;
        }

        private int arity7(String arg1, String arg2, String arg3, String arg4, String arg5, String arg6,
                           String arg7) {
            return 7;
        }

        private int arity8(String arg1, String arg2, String arg3, String arg4, String arg5, String arg6,
                           String arg7, String arg8) {
            return 8;
        }

        private int arity9(String arg1, String arg2, String arg3, String arg4, String arg5, String arg6,
                           String arg7, String arg8, String arg9) {
            return 9;
        }

        private int arity10(String arg1, String arg2, String arg3, String arg4, String arg5, String arg6,
                            String arg7, String arg8, String arg9, String arg10) {
            return 10;
        }

        private static int staticArity0() {
            return 0;
        }

        private static int staticArity1(String arg1) {
            return 1;
        }

        private static int staticArity2(String arg1, String arg2) {
            return 2;
        }

        private static int staticArity3(String arg1, String arg2, String arg3) {
            return 3;
        }

        private static int staticArity4(String arg1, String arg2, String arg3, String arg4) {
            return 4;
        }

        private static int staticArity5(String arg1, String arg2, String arg3, String arg4, String arg5) {
            return 5;
        }

        private static int staticArity6(String arg1, String arg2, String arg3, String arg4, String arg5,
                                        String arg6) {
            return 6;
        }

        private static int staticArity7(String arg1, String arg2, String arg3, String arg4, String arg5,
                                        String arg6, String arg7) {
            return 7;
        }

        private static int staticArity8(String arg1, String arg2, String arg3, String arg4, String arg5,
                                        String arg6, String arg7, String arg8) {
            return 8;
        }

        private static int staticArity9(String arg1, String arg2, String arg3, String arg4, String arg5,
                                        String arg6, String arg7, String arg8, String arg9) {
            return 9;
        }

        private static int staticArity10(String arg1, String arg2, String arg3, String arg4, String arg5,
                                         String arg6, String arg7, String arg8, String arg9, String arg10) {
            return 10;
        }
    }

    @SuppressWarnings("unused")
    static class ArityConstructedTarget {
        private final int arity;

        private ArityConstructedTarget() {
            this.arity = 0;
        }

        private ArityConstructedTarget(String arg1) {
            this.arity = 1;
        }

        private ArityConstructedTarget(String arg1, String arg2) {
            this.arity = 2;
        }

        private ArityConstructedTarget(String arg1, String arg2, String arg3) {
            this.arity = 3;
        }

        private ArityConstructedTarget(String arg1, String arg2, String arg3, String arg4) {
            this.arity = 4;
        }

        private ArityConstructedTarget(String arg1, String arg2, String arg3, String arg4, String arg5) {
            this.arity = 5;
        }

        private ArityConstructedTarget(String arg1, String arg2, String arg3, String arg4, String arg5,
                                       String arg6) {
            this.arity = 6;
        }

        private ArityConstructedTarget(String arg1, String arg2, String arg3, String arg4, String arg5,
                                       String arg6, String arg7) {
            this.arity = 7;
        }

        private ArityConstructedTarget(String arg1, String arg2, String arg3, String arg4, String arg5,
                                       String arg6, String arg7, String arg8) {
            this.arity = 8;
        }

        private ArityConstructedTarget(String arg1, String arg2, String arg3, String arg4, String arg5,
                                       String arg6, String arg7, String arg8, String arg9) {
            this.arity = 9;
        }

        private ArityConstructedTarget(String arg1, String arg2, String arg3, String arg4, String arg5,
                                       String arg6, String arg7, String arg8, String arg9, String arg10) {
            this.arity = 10;
        }
    }
}
