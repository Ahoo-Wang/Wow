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

interface MethodHandler {
    Object invoke(Object target, Object[] args) throws Throwable;

    Object invoke0(Object target) throws Throwable;

    Object invoke1(Object target, Object arg1) throws Throwable;

    Object invoke2(Object target, Object arg1, Object arg2) throws Throwable;

    Object invoke3(Object target, Object arg1, Object arg2, Object arg3) throws Throwable;

    Object invoke4(Object target, Object arg1, Object arg2, Object arg3, Object arg4) throws Throwable;

    Object invoke5(Object target, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5)
            throws Throwable;

    Object invoke6(Object target, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5,
                   Object arg6) throws Throwable;

    Object invoke7(Object target, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5,
                   Object arg6, Object arg7) throws Throwable;

    Object invoke8(Object target, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5,
                   Object arg6, Object arg7, Object arg8) throws Throwable;

    Object invoke9(Object target, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5,
                   Object arg6, Object arg7, Object arg8, Object arg9) throws Throwable;
}
