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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

class FunctionInvokerFactoryTest {

    @Test
    void createInstanceMethodReturnsInstanceFunctionInvoker() throws NoSuchMethodException {
        Method method = Target.class.getDeclaredMethod("hidden", String.class);
        method.trySetAccessible();

        FunctionInvoker invoker = FunctionInvokerFactory.create(method);

        assertThat(invoker).isInstanceOf(InstanceFunctionInvoker.class);
        assertThat(invoker).isNotInstanceOf(StaticFunctionInvoker.class);
        assertThat(invoker.parameterCount()).isEqualTo(1);
    }

    @Test
    void createStaticMethodReturnsStaticFunctionInvoker() throws NoSuchMethodException {
        Method method = Target.class.getDeclaredMethod("staticHello", String.class);
        method.trySetAccessible();

        FunctionInvoker invoker = FunctionInvokerFactory.create(method);

        assertThat(invoker).isInstanceOf(StaticFunctionInvoker.class);
        assertThat(invoker).isInstanceOf(ReceiverlessFunctionInvoker.class);
        assertThat(invoker).isNotInstanceOf(InstanceFunctionInvoker.class);
        assertThat(invoker.parameterCount()).isEqualTo(1);
    }

    @Test
    void createConstructorReturnsConstructorFunctionInvoker() throws NoSuchMethodException {
        Constructor<OneArgTarget> constructor = OneArgTarget.class.getDeclaredConstructor(String.class);
        constructor.trySetAccessible();

        ConstructorFunctionInvoker invoker = FunctionInvokerFactory.create(constructor);

        assertThat(invoker).isInstanceOf(ReceiverlessFunctionInvoker.class);
        assertThat(invoker).isNotInstanceOf(StaticFunctionInvoker.class);
        assertThat(invoker.parameterCount()).isEqualTo(1);
    }

    @Test
    void invoke1PrivateInstanceMethod() throws Throwable {
        Method method = Target.class.getDeclaredMethod("hidden", String.class);
        method.trySetAccessible();
        InstanceFunctionInvoker invoker = (InstanceFunctionInvoker) FunctionInvokerFactory.create(method);

        Object result = invoker.invoke1(new Target(), "wow");

        assertThat(result).isEqualTo("hello wow");
    }

    @Test
    void invokeFlattenedArgumentsForInstanceMethodRequiresReceiverAsFirstArgument() throws Throwable {
        Method method = Target.class.getDeclaredMethod("hidden", String.class);
        method.trySetAccessible();
        FunctionInvoker invoker = FunctionInvokerFactory.create(method);
        Target target = new Target();

        Object result = invoker.invoke(new Object[]{target, "wow"});

        assertThat(result).isEqualTo("hello wow");
    }

    @Test
    void invokeFlattenedArgumentsForInstanceMethodRequiresReceiver() throws NoSuchMethodException {
        Method method = Target.class.getDeclaredMethod("hidden", String.class);
        method.trySetAccessible();
        FunctionInvoker invoker = FunctionInvokerFactory.create(method);

        assertThatThrownBy(() -> invoker.invoke(new Object[0]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("receiver");
    }

    @Test
    void invokeFlattenedArgumentsForInstanceMethodPreservesTenArgBusinessException() throws NoSuchMethodException {
        Method method = Target.class.getDeclaredMethod("tenArgBusinessNpe", String.class, String.class, String.class,
            String.class, String.class, String.class, String.class, String.class, String.class, String.class);
        method.trySetAccessible();
        FunctionInvoker invoker = FunctionInvokerFactory.create(method);
        Target target = new Target();

        assertThatThrownBy(() -> invoker.invoke(new Object[]{
            target, "1", "2", "3", "4", "5", "6", "7", "8", "9", "10"
        })).isInstanceOf(NullPointerException.class)
            .isNotInstanceOf(IllegalArgumentException.class)
            .hasMessage("business");
    }

    @Test
    void invoke1StaticMethodWithoutTarget() throws Throwable {
        Method method = Target.class.getDeclaredMethod("staticHello", String.class);
        method.trySetAccessible();
        StaticFunctionInvoker invoker = (StaticFunctionInvoker) FunctionInvokerFactory.create(method);

        Object result = invoker.invoke1("wow");

        assertThat(result).isEqualTo("static wow");
    }

    @Test
    void invokeFlattenedArgumentsForStaticMethodDoesNotRequireReceiver() throws Throwable {
        Method method = Target.class.getDeclaredMethod("staticHello", String.class);
        method.trySetAccessible();
        FunctionInvoker invoker = FunctionInvokerFactory.create(method);

        Object result = invoker.invoke(new Object[]{"wow"});

        assertThat(result).isEqualTo("static wow");
    }

    @Test
    void invoke1PrivateConstructorWithoutTarget() throws Throwable {
        Constructor<OneArgTarget> constructor = OneArgTarget.class.getDeclaredConstructor(String.class);
        constructor.trySetAccessible();
        ConstructorFunctionInvoker invoker = FunctionInvokerFactory.create(constructor);

        Object result = invoker.invoke1("created");

        assertThat(result).isInstanceOf(OneArgTarget.class);
        assertThat(((OneArgTarget) result).value).isEqualTo("created");
    }

    @Test
    void invokeFlattenedArgumentsForConstructorDoesNotRequireReceiver() throws Throwable {
        Constructor<OneArgTarget> constructor = OneArgTarget.class.getDeclaredConstructor(String.class);
        constructor.trySetAccessible();
        FunctionInvoker invoker = FunctionInvokerFactory.create(constructor);

        Object result = invoker.invoke(new Object[]{"created"});

        assertThat(result).isInstanceOf(OneArgTarget.class);
        assertThat(((OneArgTarget) result).value).isEqualTo("created");
    }

    @Test
    void invokeConstructorArrayShouldRouteThroughArityFastPath() throws Throwable {
        Constructor<TwoArgTarget> constructor = TwoArgTarget.class.getDeclaredConstructor(String.class, String.class);
        constructor.trySetAccessible();
        ConstructorFunctionInvoker invoker = FunctionInvokerFactory.create(constructor);

        Object result = invoker.invoke(new Object[]{"created", "tenant"});

        assertThat(result).isInstanceOf(TwoArgTarget.class);
        assertThat(((TwoArgTarget) result).value).isEqualTo("created:tenant");
    }

    @Test
    void invoke1ConstructorPropagatesBusinessException() throws NoSuchMethodException {
        Constructor<FailingTarget> constructor = FailingTarget.class.getDeclaredConstructor(String.class);
        constructor.trySetAccessible();
        ConstructorFunctionInvoker invoker = FunctionInvokerFactory.create(constructor);

        assertThatThrownBy(() -> invoker.invoke1("bad"))
            .isInstanceOf(IllegalStateException.class)
            .isNotInstanceOf(InvocationTargetException.class)
            .hasMessage("boom bad");
    }

    @Test
    void invoke1ConstructorWithWrongArgumentTypeThrowsIllegalArgumentException() throws NoSuchMethodException {
        Constructor<OneArgTarget> constructor = OneArgTarget.class.getDeclaredConstructor(String.class);
        constructor.trySetAccessible();
        ConstructorFunctionInvoker invoker = FunctionInvokerFactory.create(constructor);

        assertThatThrownBy(() -> invoker.invoke1(1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    static class Target {
        private String hidden(String value) {
            return "hello " + value;
        }

        private static String staticHello(String value) {
            return "static " + value;
        }

        @SuppressWarnings("unused")
        private void tenArgBusinessNpe(String arg1, String arg2, String arg3, String arg4, String arg5,
                                       String arg6, String arg7, String arg8, String arg9, String arg10) {
            throw new NullPointerException("business");
        }
    }

    static class OneArgTarget {
        private final String value;

        private OneArgTarget(String value) {
            this.value = value;
        }
    }

    static class TwoArgTarget {
        private final String value;

        private TwoArgTarget(String value, String tenantId) {
            this.value = value + ":" + tenantId;
        }
    }

    static class FailingTarget {
        private FailingTarget(String value) {
            throw new IllegalStateException("boom " + value);
        }
    }
}
