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

public interface InstanceFunctionInvoker extends FunctionInvoker {
    Object invoke(Object receiver, Object[] args) throws Throwable;

    default Object invoke0(Object receiver) throws Throwable {
        return invoke(receiver, InvocationArguments.EMPTY_ARGS);
    }

    default Object invoke1(Object receiver, Object arg1) throws Throwable {
        return invoke(receiver, new Object[]{arg1});
    }

    default Object invoke2(Object receiver, Object arg1, Object arg2) throws Throwable {
        return invoke(receiver, new Object[]{arg1, arg2});
    }

    default Object invoke3(Object receiver, Object arg1, Object arg2, Object arg3) throws Throwable {
        return invoke(receiver, new Object[]{arg1, arg2, arg3});
    }

    default Object invoke4(Object receiver, Object arg1, Object arg2, Object arg3, Object arg4) throws Throwable {
        return invoke(receiver, new Object[]{arg1, arg2, arg3, arg4});
    }

    default Object invoke5(Object receiver, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5)
            throws Throwable {
        return invoke(receiver, new Object[]{arg1, arg2, arg3, arg4, arg5});
    }

    default Object invoke6(Object receiver, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5,
                           Object arg6) throws Throwable {
        return invoke(receiver, new Object[]{arg1, arg2, arg3, arg4, arg5, arg6});
    }

    default Object invoke7(Object receiver, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5,
                           Object arg6, Object arg7) throws Throwable {
        return invoke(receiver, new Object[]{arg1, arg2, arg3, arg4, arg5, arg6, arg7});
    }

    default Object invoke8(Object receiver, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5,
                           Object arg6, Object arg7, Object arg8) throws Throwable {
        return invoke(receiver, new Object[]{arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8});
    }

    default Object invoke9(Object receiver, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5,
                           Object arg6, Object arg7, Object arg8, Object arg9) throws Throwable {
        return invoke(receiver, new Object[]{arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9});
    }
}
