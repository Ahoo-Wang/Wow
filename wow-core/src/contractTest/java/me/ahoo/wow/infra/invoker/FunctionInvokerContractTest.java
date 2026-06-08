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

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FunctionInvokerContractTest {

    @Test
    void methodHandleInvokersSupportArrayArityAndFallbackPaths() throws Throwable {
        ArityTarget target = new ArityTarget();
        for (int arity = 0; arity <= 10; arity++) {
            Method instanceMethod = ArityTarget.class.getDeclaredMethod("arity" + arity, parameterTypes(arity));
            instanceMethod.trySetAccessible();
            InstanceFunctionInvoker instanceInvoker = (InstanceFunctionInvoker) FunctionInvokerFactory
                .create(instanceMethod);
            assertThat(instanceInvoker.invoke(target, arguments(arity))).isEqualTo(arity);

            Method staticMethod = ArityTarget.class.getDeclaredMethod("staticArity" + arity, parameterTypes(arity));
            staticMethod.trySetAccessible();
            StaticFunctionInvoker staticInvoker = (StaticFunctionInvoker) FunctionInvokerFactory.create(staticMethod);
            assertThat(staticInvoker.invoke(arguments(arity))).isEqualTo(arity);

            Constructor<ArityConstructedTarget> constructor = ArityConstructedTarget.class
                .getDeclaredConstructor(parameterTypes(arity));
            constructor.trySetAccessible();
            ConstructorFunctionInvoker constructorInvoker = FunctionInvokerFactory.create(constructor);
            assertThat(((ArityConstructedTarget) constructorInvoker.invoke(arguments(arity))).arity).isEqualTo(arity);
        }
    }

    @Test
    void defaultArityMethodsDelegateToArrayInvocation() throws Throwable {
        RecordingInstanceInvoker instanceInvoker = new RecordingInstanceInvoker();
        RecordingReceiverlessInvoker receiverlessInvoker = new RecordingReceiverlessInvoker();
        Object receiver = new Object();

        instanceInvoker.invoke0(receiver);
        assertThat(instanceInvoker.lastReceiver).isSameAs(receiver);
        assertThat(instanceInvoker.lastArgs).isEmpty();
        receiverlessInvoker.invoke0();
        assertThat(receiverlessInvoker.lastArgs).isEmpty();

        instanceInvoker.invoke1(receiver, "1");
        assertThat(instanceInvoker.lastArgs).containsExactly("1");
        receiverlessInvoker.invoke1("1");
        assertThat(receiverlessInvoker.lastArgs).containsExactly("1");

        instanceInvoker.invoke2(receiver, "1", "2");
        assertThat(instanceInvoker.lastArgs).containsExactly("1", "2");
        receiverlessInvoker.invoke2("1", "2");
        assertThat(receiverlessInvoker.lastArgs).containsExactly("1", "2");

        instanceInvoker.invoke3(receiver, "1", "2", "3");
        assertThat(instanceInvoker.lastArgs).containsExactly("1", "2", "3");
        receiverlessInvoker.invoke3("1", "2", "3");
        assertThat(receiverlessInvoker.lastArgs).containsExactly("1", "2", "3");

        instanceInvoker.invoke4(receiver, "1", "2", "3", "4");
        assertThat(instanceInvoker.lastArgs).containsExactly("1", "2", "3", "4");
        receiverlessInvoker.invoke4("1", "2", "3", "4");
        assertThat(receiverlessInvoker.lastArgs).containsExactly("1", "2", "3", "4");

        instanceInvoker.invoke5(receiver, "1", "2", "3", "4", "5");
        assertThat(instanceInvoker.lastArgs).containsExactly("1", "2", "3", "4", "5");
        receiverlessInvoker.invoke5("1", "2", "3", "4", "5");
        assertThat(receiverlessInvoker.lastArgs).containsExactly("1", "2", "3", "4", "5");

        instanceInvoker.invoke6(receiver, "1", "2", "3", "4", "5", "6");
        assertThat(instanceInvoker.lastArgs).containsExactly("1", "2", "3", "4", "5", "6");
        receiverlessInvoker.invoke6("1", "2", "3", "4", "5", "6");
        assertThat(receiverlessInvoker.lastArgs).containsExactly("1", "2", "3", "4", "5", "6");

        instanceInvoker.invoke7(receiver, "1", "2", "3", "4", "5", "6", "7");
        assertThat(instanceInvoker.lastArgs).containsExactly("1", "2", "3", "4", "5", "6", "7");
        receiverlessInvoker.invoke7("1", "2", "3", "4", "5", "6", "7");
        assertThat(receiverlessInvoker.lastArgs).containsExactly("1", "2", "3", "4", "5", "6", "7");

        instanceInvoker.invoke8(receiver, "1", "2", "3", "4", "5", "6", "7", "8");
        assertThat(instanceInvoker.lastArgs).containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
        receiverlessInvoker.invoke8("1", "2", "3", "4", "5", "6", "7", "8");
        assertThat(receiverlessInvoker.lastArgs).containsExactly("1", "2", "3", "4", "5", "6", "7", "8");

        instanceInvoker.invoke9(receiver, "1", "2", "3", "4", "5", "6", "7", "8", "9");
        assertThat(instanceInvoker.lastArgs).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
        receiverlessInvoker.invoke9("1", "2", "3", "4", "5", "6", "7", "8", "9");
        assertThat(receiverlessInvoker.lastArgs).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
    }

    @Test
    void invalidMethodHandleCallsAreNormalizedButBusinessErrorsArePreserved() throws Throwable {
        Method method = ErrorTarget.class.getDeclaredMethod("echo", String.class);
        method.trySetAccessible();
        InstanceFunctionInvoker invoker = (InstanceFunctionInvoker) FunctionInvokerFactory.create(method);

        assertThatThrownBy(() -> invoker.invoke1(null, "wow"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> invoker.invoke1(new Object(), "wow"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> invoker.invoke0(new ErrorTarget()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> invoker.invoke1(new ErrorTarget(), 1))
            .isInstanceOf(IllegalArgumentException.class);

        Method primitiveMethod = ErrorTarget.class.getDeclaredMethod("primitiveBusinessNpe", boolean.class, byte.class,
            char.class, short.class, int.class, long.class, float.class, double.class);
        primitiveMethod.trySetAccessible();
        InstanceFunctionInvoker primitiveInvoker = (InstanceFunctionInvoker) FunctionInvokerFactory
            .create(primitiveMethod);
        assertThatThrownBy(() -> primitiveInvoker.invoke(new ErrorTarget(), new Object[]{
            Boolean.TRUE, (byte) 1, 'c', (byte) 2, 'i', 3, 4L, 5F
        })).isInstanceOf(NullPointerException.class)
            .isNotInstanceOf(IllegalArgumentException.class)
            .hasMessage("primitive business");

        Method nullPrimitiveMethod = ErrorTarget.class.getDeclaredMethod("intValue", int.class);
        nullPrimitiveMethod.trySetAccessible();
        InstanceFunctionInvoker nullPrimitiveInvoker = (InstanceFunctionInvoker) FunctionInvokerFactory
            .create(nullPrimitiveMethod);
        assertThatThrownBy(() -> nullPrimitiveInvoker.invoke1(new ErrorTarget(), null))
            .isInstanceOf(IllegalArgumentException.class);

        Method classCastMethod = ErrorTarget.class.getDeclaredMethod("businessClassCast", Object.class);
        classCastMethod.trySetAccessible();
        InstanceFunctionInvoker classCastInvoker = (InstanceFunctionInvoker) FunctionInvokerFactory
            .create(classCastMethod);
        assertThatThrownBy(() -> classCastInvoker.invoke1(new ErrorTarget(), "wow"))
            .isInstanceOf(ClassCastException.class)
            .isNotInstanceOf(IllegalArgumentException.class)
            .hasMessage("business");
    }

    @Test
    void reflectionFallbackInvokersUnwrapTargetExceptions() throws Throwable {
        Method instanceMethod = ReflectionTarget.class.getDeclaredMethod("boom", String.class);
        instanceMethod.trySetAccessible();
        ReflectionInstanceFunctionInvoker instanceInvoker = new ReflectionInstanceFunctionInvoker(instanceMethod);
        assertThat(instanceInvoker.parameterCount()).isEqualTo(1);
        assertThatThrownBy(() -> instanceInvoker.invoke1(new ReflectionTarget(), "instance"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("boom instance");

        Method staticMethod = ReflectionTarget.class.getDeclaredMethod("staticBoom", String.class);
        staticMethod.trySetAccessible();
        ReflectionStaticFunctionInvoker staticInvoker = new ReflectionStaticFunctionInvoker(staticMethod);
        assertThat(staticInvoker.parameterCount()).isEqualTo(1);
        assertThatThrownBy(() -> staticInvoker.invoke1("static"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("boom static");

        Constructor<ReflectionTarget> constructor = ReflectionTarget.class.getDeclaredConstructor(String.class);
        constructor.trySetAccessible();
        ReflectionConstructorFunctionInvoker constructorInvoker = new ReflectionConstructorFunctionInvoker(constructor);
        assertThat(constructorInvoker.parameterCount()).isEqualTo(1);
        assertThatThrownBy(() -> constructorInvoker.invoke1("constructor"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("boom constructor");
    }

    @Test
    void factoryFallsBackToReflectionWhenMembersAreNotAccessible() throws NoSuchMethodException {
        Method instanceMethod = InaccessibleTarget.class.getDeclaredMethod("hidden", String.class);
        assertThat(FunctionInvokerFactory.create(instanceMethod)).isInstanceOf(ReflectionInstanceFunctionInvoker.class);

        Method staticMethod = InaccessibleTarget.class.getDeclaredMethod("staticHidden", String.class);
        assertThat(FunctionInvokerFactory.create(staticMethod)).isInstanceOf(ReflectionStaticFunctionInvoker.class);

        Constructor<InaccessibleTarget> constructor = InaccessibleTarget.class.getDeclaredConstructor(String.class);
        assertThat(FunctionInvokerFactory.create(constructor)).isInstanceOf(ReflectionConstructorFunctionInvoker.class);
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

    static class RecordingInstanceInvoker implements InstanceFunctionInvoker {
        Object lastReceiver;
        Object[] lastArgs;

        @Override
        public int parameterCount() {
            return 0;
        }

        @Override
        public Object invoke(Object receiver, Object[] args) {
            lastReceiver = receiver;
            lastArgs = args;
            return null;
        }
    }

    static class RecordingReceiverlessInvoker implements StaticFunctionInvoker {
        Object[] lastArgs;

        @Override
        public int parameterCount() {
            return 0;
        }

        @Override
        public Object invoke(Object[] args) {
            lastArgs = args;
            return null;
        }
    }

    @SuppressWarnings("unused")
    static class ErrorTarget {
        private String echo(String value) {
            return value;
        }

        private int intValue(int value) {
            return value;
        }

        private void primitiveBusinessNpe(boolean booleanValue, byte byteValue, char charValue, short shortValue,
                                          int intValue, long longValue, float floatValue, double doubleValue) {
            throw new NullPointerException("primitive business");
        }

        private void businessClassCast(Object value) {
            throw new ClassCastException("business");
        }
    }

    @SuppressWarnings("unused")
    static class ReflectionTarget {
        private ReflectionTarget() {
        }

        private ReflectionTarget(String value) {
            throw new IllegalStateException("boom " + value);
        }

        private void boom(String value) {
            throw new IllegalStateException("boom " + value);
        }

        private static void staticBoom(String value) {
            throw new IllegalStateException("boom " + value);
        }
    }

    @SuppressWarnings("unused")
    static class InaccessibleTarget {
        private InaccessibleTarget(String value) {
        }

        private String hidden(String value) {
            return value;
        }

        private static String staticHidden(String value) {
            return value;
        }
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
