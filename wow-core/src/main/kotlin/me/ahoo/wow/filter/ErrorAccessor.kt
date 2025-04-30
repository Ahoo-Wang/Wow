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

package me.ahoo.wow.filter

/**
 * 定义错误处理的基本操作
 * 它允许错误对象的设置、获取和清除
 */
interface ErrorAccessor {
    /**
     * 设置错误对象
     *
     * @param throwable 要设置的错误对象，类型为Throwable
     */
    fun setError(throwable: Throwable)

    /**
     * 获取当前错误对象
     *
     * @return 当前的错误对象，如果有的话；如果没有错误，则返回null
     */
    fun getError(): Throwable?

    /**
     * 清除当前的错误对象
     * 调用此方法后，getError()应该返回null
     */
    fun clearError()
}
