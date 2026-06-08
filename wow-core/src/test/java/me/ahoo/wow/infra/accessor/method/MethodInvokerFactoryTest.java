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

package me.ahoo.wow.infra.accessor.method;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

class MethodInvokerFactoryTest {

    @Test
    void invokeSinglePrivateMethod() throws Throwable {
        Method method = Target.class.getDeclaredMethod("hidden", String.class);
        method.trySetAccessible();
        MethodInvoker invoker = MethodInvokerFactory.create(method);

        Object result = invoker.invoke1(new Target(), "wow");

        assertThat(result).isEqualTo("hello wow");
    }

    @Test
    void createInstanceMethodReturnsInstanceMethodInvoker() throws NoSuchMethodException {
        Method method = Target.class.getDeclaredMethod("hidden", String.class);
        method.trySetAccessible();

        MethodInvoker invoker = MethodInvokerFactory.create(method);

        assertThat(invoker).isInstanceOf(InstanceMethodInvoker.class);
    }

    @Test
    void createStaticMethodReturnsStaticMethodInvoker() throws NoSuchMethodException {
        Method method = Target.class.getDeclaredMethod("staticHello", String.class);
        method.trySetAccessible();

        MethodInvoker invoker = MethodInvokerFactory.create(method);

        assertThat(invoker).isInstanceOf(StaticMethodInvoker.class);
    }

    @Test
    void invokePrivateTwoArgMethod() throws Throwable {
        Method method = Target.class.getDeclaredMethod("join", String.class, String.class);
        method.trySetAccessible();
        MethodInvoker invoker = MethodInvokerFactory.create(method);

        Object result = invoker.invoke2(new Target(), "a", "b");

        assertThat(result).isEqualTo("a-b");
    }

    @Test
    void invokeNoArgMethod() throws Throwable {
        Method method = Target.class.getDeclaredMethod("hello");
        method.trySetAccessible();
        MethodInvoker invoker = MethodInvokerFactory.create(method);

        Object result = invoker.invoke0(new Target());

        assertThat(result).isEqualTo("hello");
    }

    @Test
    void invokeSingleStaticMethodWithNullTarget() throws Throwable {
        Method method = Target.class.getDeclaredMethod("staticHello", String.class);
        method.trySetAccessible();
        MethodInvoker invoker = MethodInvokerFactory.create(method);

        Object result = invoker.invoke1(null, "wow");

        assertThat(result).isEqualTo("static wow");
    }

    @Test
    void invokePreservesVarargsArrayArgument() throws Throwable {
        Method method = Target.class.getDeclaredMethod("varargs", String[].class);
        method.trySetAccessible();
        MethodInvoker invoker = MethodInvokerFactory.create(method);
        String[] args = {"a", "b"};

        Object result = invoker.invoke(new Target(), new Object[]{args});

        assertThat(result).isSameAs(args);
    }

    @Test
    void invokeSinglePropagatesBusinessException() throws NoSuchMethodException {
        Method method = Target.class.getDeclaredMethod("boom", String.class);
        method.trySetAccessible();
        MethodInvoker invoker = MethodInvokerFactory.create(method);

        assertThatThrownBy(() -> invoker.invoke1(new Target(), "wow"))
            .isInstanceOf(IllegalStateException.class)
            .isNotInstanceOf(InvocationTargetException.class)
            .hasMessage("boom wow");
    }

    @Test
    void invokeSingleWithWrongArgumentTypeThrowsIllegalArgumentException() throws NoSuchMethodException {
        Method method = Target.class.getDeclaredMethod("hidden", String.class);
        method.trySetAccessible();
        MethodInvoker invoker = MethodInvokerFactory.create(method);

        assertThatThrownBy(() -> invoker.invoke1(new Target(), 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invokeSingleWithWrongArityThrowsIllegalArgumentException() throws NoSuchMethodException {
        Method method = Target.class.getDeclaredMethod("hello");
        method.trySetAccessible();
        MethodInvoker invoker = MethodInvokerFactory.create(method);

        assertThatThrownBy(() -> invoker.invoke1(new Target(), "wow"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invokeWithWrongArgumentCountThrowsIllegalArgumentException() throws NoSuchMethodException {
        Method method = Target.class.getDeclaredMethod("join", String.class, String.class);
        method.trySetAccessible();
        MethodInvoker invoker = MethodInvokerFactory.create(method);

        assertThatThrownBy(() -> invoker.invoke(new Target(), new Object[]{"a"}))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invokeSingleWithNullInstanceTargetThrowsIllegalArgumentException() throws NoSuchMethodException {
        Method method = Target.class.getDeclaredMethod("hidden", String.class);
        method.trySetAccessible();
        MethodInvoker invoker = MethodInvokerFactory.create(method);

        assertThatThrownBy(() -> invoker.invoke1(null, "wow"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invokeSingleWithWrongInstanceTargetThrowsIllegalArgumentException() throws NoSuchMethodException {
        Method method = Target.class.getDeclaredMethod("hidden", String.class);
        method.trySetAccessible();
        MethodInvoker invoker = MethodInvokerFactory.create(method);

        assertThatThrownBy(() -> invoker.invoke1(new Object(), "wow"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invokeSinglePropagatesBusinessClassCastException() throws NoSuchMethodException {
        Method method = Target.class.getDeclaredMethod("classCastBoom", Object.class);
        method.trySetAccessible();
        MethodInvoker invoker = MethodInvokerFactory.create(method);

        assertThatThrownBy(() -> invoker.invoke1(new Target(), "wow"))
            .isInstanceOf(ClassCastException.class)
            .isNotInstanceOf(IllegalArgumentException.class)
            .hasMessage("business");
    }

    static class Target {

        private String hidden(String value) {
            return "hello " + value;
        }

        private String join(String first, String second) {
            return first + "-" + second;
        }

        private String hello() {
            return "hello";
        }

        private static String staticHello(String value) {
            return "static " + value;
        }

        private String[] varargs(String... args) {
            return args;
        }

        private void boom(String value) {
            throw new IllegalStateException("boom " + value);
        }

        private void classCastBoom(Object value) {
            throw new ClassCastException("business");
        }
    }
}
