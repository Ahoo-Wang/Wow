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
import java.lang.reflect.Method;

final class StaticMethodInvoker implements MethodHandler {
    private final Method method;
    private final MethodHandle handle;

    StaticMethodInvoker(Method method, MethodHandle handle) {
        this.method = method;
        this.handle = handle;
    }

    @Override
    public Object invoke(Object target, Object[] args) throws Throwable {
        Object[] actualArgs = MethodInvocationSupport.actualArgs(args);
        try {
            if (actualArgs.length <= 9) {
                return MethodInvocationSupport.invokeByArgumentArray(this, target, actualArgs);
            }
            return handle.invokeWithArguments(actualArgs);
        } catch (Throwable error) {
            throw normalize(error, target, actualArgs);
        }
    }

    @Override
    public Object invoke0(Object target) throws Throwable {
        try {
            return handle.invoke();
        } catch (Throwable error) {
            throw normalize(error, target, MethodInvocationSupport.EMPTY_ARGS);
        }
    }

    @Override
    public Object invoke1(Object target, Object arg1) throws Throwable {
        try {
            return handle.invoke(arg1);
        } catch (Throwable error) {
            throw normalize(error, target, arg1);
        }
    }

    @Override
    public Object invoke2(Object target, Object arg1, Object arg2) throws Throwable {
        try {
            return handle.invoke(arg1, arg2);
        } catch (Throwable error) {
            throw normalize(error, target, arg1, arg2);
        }
    }

    @Override
    public Object invoke3(Object target, Object arg1, Object arg2, Object arg3) throws Throwable {
        try {
            return handle.invoke(arg1, arg2, arg3);
        } catch (Throwable error) {
            throw normalize(error, target, arg1, arg2, arg3);
        }
    }

    @Override
    public Object invoke4(Object target, Object arg1, Object arg2, Object arg3, Object arg4) throws Throwable {
        try {
            return handle.invoke(arg1, arg2, arg3, arg4);
        } catch (Throwable error) {
            throw normalize(error, target, arg1, arg2, arg3, arg4);
        }
    }

    @Override
    public Object invoke5(Object target, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5)
            throws Throwable {
        try {
            return handle.invoke(arg1, arg2, arg3, arg4, arg5);
        } catch (Throwable error) {
            throw normalize(error, target, arg1, arg2, arg3, arg4, arg5);
        }
    }

    @Override
    public Object invoke6(Object target, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5,
                          Object arg6) throws Throwable {
        try {
            return handle.invoke(arg1, arg2, arg3, arg4, arg5, arg6);
        } catch (Throwable error) {
            throw normalize(error, target, arg1, arg2, arg3, arg4, arg5, arg6);
        }
    }

    @Override
    public Object invoke7(Object target, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5,
                          Object arg6, Object arg7) throws Throwable {
        try {
            return handle.invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7);
        } catch (Throwable error) {
            throw normalize(error, target, arg1, arg2, arg3, arg4, arg5, arg6, arg7);
        }
    }

    @Override
    public Object invoke8(Object target, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5,
                          Object arg6, Object arg7, Object arg8) throws Throwable {
        try {
            return handle.invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8);
        } catch (Throwable error) {
            throw normalize(error, target, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8);
        }
    }

    @Override
    public Object invoke9(Object target, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5,
                          Object arg6, Object arg7, Object arg8, Object arg9) throws Throwable {
        try {
            return handle.invoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9);
        } catch (Throwable error) {
            throw normalize(error, target, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9);
        }
    }

    private Throwable normalize(Throwable error, Object target, Object[] args) {
        return MethodInvocationSupport.normalizeInvocationException(error, method, true, target, args);
    }

    private Throwable normalize(Throwable error, Object target, Object arg1) {
        return MethodInvocationSupport.normalizeInvocationException(error, method, true, target, arg1);
    }

    private Throwable normalize(Throwable error, Object target, Object arg1, Object arg2) {
        return MethodInvocationSupport.normalizeInvocationException(error, method, true, target, arg1, arg2);
    }

    private Throwable normalize(Throwable error, Object target, Object arg1, Object arg2, Object arg3) {
        return MethodInvocationSupport.normalizeInvocationException(error, method, true, target, arg1, arg2, arg3);
    }

    private Throwable normalize(Throwable error, Object target, Object arg1, Object arg2, Object arg3,
                                Object arg4) {
        return MethodInvocationSupport.normalizeInvocationException(error, method, true, target, arg1, arg2, arg3,
            arg4);
    }

    private Throwable normalize(Throwable error, Object target, Object arg1, Object arg2, Object arg3,
                                Object arg4, Object arg5) {
        return MethodInvocationSupport.normalizeInvocationException(error, method, true, target, arg1, arg2, arg3,
            arg4, arg5);
    }

    private Throwable normalize(Throwable error, Object target, Object arg1, Object arg2, Object arg3,
                                Object arg4, Object arg5, Object arg6) {
        return MethodInvocationSupport.normalizeInvocationException(error, method, true, target, arg1, arg2, arg3,
            arg4, arg5, arg6);
    }

    private Throwable normalize(Throwable error, Object target, Object arg1, Object arg2, Object arg3,
                                Object arg4, Object arg5, Object arg6, Object arg7) {
        return MethodInvocationSupport.normalizeInvocationException(error, method, true, target, arg1, arg2, arg3,
            arg4, arg5, arg6, arg7);
    }

    private Throwable normalize(Throwable error, Object target, Object arg1, Object arg2, Object arg3,
                                Object arg4, Object arg5, Object arg6, Object arg7, Object arg8) {
        return MethodInvocationSupport.normalizeInvocationException(error, method, true, target, arg1, arg2, arg3,
            arg4, arg5, arg6, arg7, arg8);
    }

    private Throwable normalize(Throwable error, Object target, Object arg1, Object arg2, Object arg3,
                                Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9) {
        return MethodInvocationSupport.normalizeInvocationException(error, method, true, target, arg1, arg2, arg3,
            arg4, arg5, arg6, arg7, arg8, arg9);
    }
}
