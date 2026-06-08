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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class ReflectionMethodInvoker implements MethodHandler {
    private final Method method;

    ReflectionMethodInvoker(Method method) {
        this.method = method;
    }

    @Override
    public Object invoke(Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException targetException) {
            throw targetException.getTargetException();
        }
    }

    @Override
    public Object invoke0(Object target) throws Throwable {
        return invoke(target, MethodInvocationSupport.EMPTY_ARGS);
    }

    @Override
    public Object invoke1(Object target, Object arg1) throws Throwable {
        return invoke(target, new Object[]{arg1});
    }

    @Override
    public Object invoke2(Object target, Object arg1, Object arg2) throws Throwable {
        return invoke(target, new Object[]{arg1, arg2});
    }

    @Override
    public Object invoke3(Object target, Object arg1, Object arg2, Object arg3) throws Throwable {
        return invoke(target, new Object[]{arg1, arg2, arg3});
    }

    @Override
    public Object invoke4(Object target, Object arg1, Object arg2, Object arg3, Object arg4) throws Throwable {
        return invoke(target, new Object[]{arg1, arg2, arg3, arg4});
    }

    @Override
    public Object invoke5(Object target, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5)
            throws Throwable {
        return invoke(target, new Object[]{arg1, arg2, arg3, arg4, arg5});
    }

    @Override
    public Object invoke6(Object target, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5,
                          Object arg6) throws Throwable {
        return invoke(target, new Object[]{arg1, arg2, arg3, arg4, arg5, arg6});
    }

    @Override
    public Object invoke7(Object target, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5,
                          Object arg6, Object arg7) throws Throwable {
        return invoke(target, new Object[]{arg1, arg2, arg3, arg4, arg5, arg6, arg7});
    }

    @Override
    public Object invoke8(Object target, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5,
                          Object arg6, Object arg7, Object arg8) throws Throwable {
        return invoke(target, new Object[]{arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8});
    }

    @Override
    public Object invoke9(Object target, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5,
                          Object arg6, Object arg7, Object arg8, Object arg9) throws Throwable {
        return invoke(target, new Object[]{arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9});
    }
}
