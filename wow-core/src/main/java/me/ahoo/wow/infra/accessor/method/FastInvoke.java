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

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Utility class providing fast method invocation and object instantiation using Java reflection.
 * <p>
 * This class offers optimized methods for invoking methods and creating instances with improved
 * exception handling. It avoids the performance overhead of the spread operator by using Object[]
 * for method arguments, addressing the Detekt SpreadOperator rule.
 * </p>
 * <p>
 * All methods in this class are thread-safe and can be used concurrently.
 * </p>
 * <p>
 * <strong>Note:</strong> This class cannot be instantiated as it contains only static utility methods.
 * </p>
 *
 * @see <a href="https://detekt.dev/docs/rules/performance/#spreadoperator">Detekt SpreadOperator Rule</a>
 */
public final class FastInvoke {
    private FastInvoke() {
    }

    /**
     * Invokes the specified method on the target object with the given arguments.
     * <p>
     * This method provides a type-safe wrapper around {@link Method#invoke(Object, Object...)}
     * while avoiding the performance penalty of the spread operator.
     * </p>
     *
     * @param method the method to invoke; must not be null
     * @param target the object on which to invoke the method, or null for static methods
     * @param args the arguments to pass to the method; may be null if the method takes no arguments
     * @param <T> the return type of the method
     * @return the result of the method invocation, or null if the method returns void
     * @throws InvocationTargetException if the underlying method throws an exception
     * @throws IllegalAccessException if the method is not accessible (e.g., private method)
     * @throws IllegalArgumentException if the method is an instance method and target is null,
     *         or if the arguments are not appropriate for the method
     *
     * @example
     * <pre>{@code
     * Method method = MyClass.class.getMethod("myMethod", String.class);
     * MyClass instance = new MyClass();
     * String result = FastInvoke.invoke(method, instance, new Object[]{"hello"});
     * }</pre>
     */
    @SuppressWarnings({"AvoidObjectArrays", "unchecked"})
    public static <T> T invoke(@NotNull Method method, Object target, Object[] args)
            throws InvocationTargetException, IllegalAccessException {
        return (T) method.invoke(target, args);
    }

    /**
     * Safely invokes the specified method on the target object, unwrapping InvocationTargetException.
     * <p>
     * This method calls {@link #invoke(Method, Object, Object[])} but throws the target exception
     * directly instead of wrapping it in InvocationTargetException, providing cleaner exception handling.
     * </p>
     *
     * @param method the method to invoke; must not be null
     * @param target the object on which to invoke the method, or null for static methods
     * @param args the arguments to pass to the method; may be null if the method takes no arguments
     * @param <T> the return type of the method
     * @return the result of the method invocation, or null if the method returns void
     * @throws Throwable if the underlying method throws an exception or if invocation fails
     * @throws IllegalAccessException if the method is not accessible
     * @throws IllegalArgumentException if the method is an instance method and target is null,
     *         or if the arguments are not appropriate for the method
     *
     * @example
     * <pre>{@code
     * Method method = MyClass.class.getMethod("myMethod", String.class);
     * MyClass instance = new MyClass();
     * try {
     *     String result = FastInvoke.safeInvoke(method, instance, new Object[]{"hello"});
     * } catch (MyCustomException e) {
     *     // Handle the actual exception thrown by myMethod
     * }
     * }</pre>
     */
    public static <T> T safeInvoke(@NotNull Method method, Object target, Object[] args)
            throws Throwable {
        try {
            return invoke(method, target, args);
        } catch (InvocationTargetException targetException) {
            throw targetException.getTargetException();
        }
    }

    /**
     * Creates a new instance of the class using the specified constructor with the given arguments.
     * <p>
     * This method provides a type-safe wrapper around {@link Constructor#newInstance(Object...)}
     * while avoiding the performance penalty of the spread operator.
     * </p>
     *
     * @param constructor the constructor to use for instantiation; must not be null
     * @param args the arguments to pass to the constructor; may be null if the constructor takes no arguments
     * @param <T> the type of the instance to create
     * @return a new instance of the class; never null
     * @throws InvocationTargetException if the constructor throws an exception
     * @throws InstantiationException if the class cannot be instantiated (e.g., abstract class, interface)
     * @throws IllegalAccessException if the constructor is not accessible
     * @throws IllegalArgumentException if the arguments are not appropriate for the constructor
     *
     * @example
     * <pre>{@code
     * Constructor<MyClass> constructor = MyClass.class.getConstructor(String.class);
     * MyClass instance = FastInvoke.newInstance(constructor, new Object[]{"initial value"});
     * }</pre>
     */
    @NotNull
    @SuppressWarnings("AvoidObjectArrays")
    public static <T> T newInstance(@NotNull Constructor<T> constructor, Object[] args)
            throws InvocationTargetException, InstantiationException,
            IllegalAccessException {
        return constructor.newInstance(args);
    }

    /**
     * Safely creates a new instance of the class, unwrapping InvocationTargetException.
     * <p>
     * This method calls {@link #newInstance(Constructor, Object[])} but throws the target exception
     * directly instead of wrapping it in InvocationTargetException, providing cleaner exception handling
     * for constructor calls.
     * </p>
     *
     * @param constructor the constructor to use for instantiation; must not be null
     * @param args the arguments to pass to the constructor; may be null if the constructor takes no arguments
     * @param <T> the type of the instance to create
     * @return a new instance of the class; never null
     * @throws Throwable if the constructor throws an exception or if instantiation fails
     * @throws InstantiationException if the class cannot be instantiated
     * @throws IllegalAccessException if the constructor is not accessible
     * @throws IllegalArgumentException if the arguments are not appropriate for the constructor
     *
     * @example
     * <pre>{@code
     * Constructor<MyClass> constructor = MyClass.class.getConstructor(String.class);
     * try {
     *     MyClass instance = FastInvoke.safeNewInstance(constructor, new Object[]{"initial value"});
     * } catch (MyCustomException e) {
     *     // Handle the actual exception thrown by the constructor
     * }
     * }</pre>
     */
    public static <T> T safeNewInstance(@NotNull Constructor<T> constructor, Object[] args)
            throws Throwable {
        try {
            return newInstance(constructor, args);
        } catch (InvocationTargetException targetException) {
            throw targetException.getTargetException();
        }
    }
}
