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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FunctionInvocationSupportTest {

    @Test
    void shouldHandleNullArgumentArrayForInstanceInvoker() throws Throwable {
        Method method = NullArgTarget.class.getDeclaredMethod("zero");
        method.trySetAccessible();
        InstanceFunctionInvoker invoker = (InstanceFunctionInvoker) FunctionInvokerFactory.create(method);

        assertThat(invoker.invoke(new NullArgTarget(), null)).isEqualTo("zero");
    }

    @Test
    void shouldHandleNullArgumentArrayForStaticInvoker() throws Throwable {
        Method method = NullArgTarget.class.getDeclaredMethod("staticZero");
        method.trySetAccessible();
        StaticFunctionInvoker invoker = (StaticFunctionInvoker) FunctionInvokerFactory.create(method);

        assertThat(invoker.invoke(null)).isEqualTo("static-zero");
    }

    @Test
    void shouldHandleNullArgumentArrayForConstructorInvoker() throws Throwable {
        Constructor<NullArgTarget> constructor = NullArgTarget.class.getDeclaredConstructor();
        constructor.trySetAccessible();
        ConstructorFunctionInvoker invoker = FunctionInvokerFactory.create(constructor);

        assertThat(((NullArgTarget) invoker.invoke(null)).name).isEqualTo("created");
    }

    @Test
    void shouldNormalizePrimitiveWrappersForInstanceMethodInvocation() throws Throwable {
        Method method = ConversionTarget.class.getDeclaredMethod(
            "acceptPrimitiveWrappers",
            boolean.class,
            byte.class,
            char.class,
            short.class,
            int.class,
            long.class,
            float.class,
            double.class
        );
        method.trySetAccessible();
        InstanceFunctionInvoker invoker = (InstanceFunctionInvoker) FunctionInvokerFactory.create(method);

        assertThat(invoker.invoke(new ConversionTarget(), new Object[]{
            true,
            (byte) 1,
            'a',
            (byte) 2,
            (char) 3,
            4,
            5L,
            6F
        })).isEqualTo("true|1|a|2|3|4|5.0|6.0");
    }

    @Test
    void shouldNormalizePrimitiveWrappersForConstructorInvocation() throws Throwable {
        Constructor<ConversionTarget> constructor = ConversionTarget.class
            .getDeclaredConstructor(
                boolean.class,
                byte.class,
                char.class,
                short.class,
                int.class,
                long.class,
                float.class,
                double.class
            );
        constructor.trySetAccessible();
        ConstructorFunctionInvoker invoker = FunctionInvokerFactory.create(constructor);

        ConversionTarget target = (ConversionTarget) invoker.invoke(new Object[]{
            true,
            (byte) 1,
            'a',
            (byte) 2,
            (char) 3,
            4,
            5L,
            6F
        });

        assertThat(target.value).isEqualTo("true|1|a|2|3|4|5.0|6.0");
    }

    @Test
    void shouldConvertInvalidPrimitiveArgumentAsIllegalArgumentExceptionForConstructor() throws Throwable {
        Constructor<ConversionTarget> constructor = ConversionTarget.class
            .getDeclaredConstructor(
                boolean.class,
                byte.class,
                char.class,
                short.class,
                int.class,
                long.class,
                float.class,
                double.class
            );
        constructor.trySetAccessible();
        ConstructorFunctionInvoker invoker = FunctionInvokerFactory.create(constructor);

        assertThatThrownBy(() -> invoker.invoke(new Object[]{
            true,
            "bad",
            'a',
            (byte) 2,
            (char) 3,
            4,
            5L,
            6F
        })).isInstanceOf(IllegalArgumentException.class);
    }

    static class NullArgTarget {
        private final String name;

        private NullArgTarget() {
            name = "created";
        }

        private String zero() {
            return "zero";
        }

        private static String staticZero() {
            return "static-zero";
        }
    }

    static class ConversionTarget {
        private final String value;

        private ConversionTarget() {
            value = "default";
        }

        private ConversionTarget(boolean boolValue, byte byteValue, char charValue,
                                short shortValue, int intValue, long longValue,
                                float floatValue, double doubleValue) {
            value = String.valueOf(boolValue)
                + "|"
                + byteValue
                + "|"
                + charValue
                + "|"
                + shortValue
                + "|"
                + intValue
                + "|"
                + longValue
                + "|"
                + floatValue
                + "|"
                + doubleValue;
        }

        private String acceptPrimitiveWrappers(boolean boolValue, byte byteValue, char charValue,
                                              short shortValue, int intValue, long longValue,
                                              float floatValue, double doubleValue) {
            return String.valueOf(boolValue)
                + "|"
                + byteValue
                + "|"
                + charValue
                + "|"
                + shortValue
                + "|"
                + intValue
                + "|"
                + longValue
                + "|"
                + floatValue
                + "|"
                + doubleValue;
        }
    }
}
