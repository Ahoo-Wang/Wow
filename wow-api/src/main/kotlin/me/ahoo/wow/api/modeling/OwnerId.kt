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

package me.ahoo.wow.api.modeling

/**
 * 用于标识资源的拥有者
 */
interface OwnerId {
    /**
     * 资源拥有者的唯一标识符
     */
    val ownerId: String

    /**
     * 伴生对象，用于定义与接口相关的常量和函数
     */
    companion object {
        /**
         * 默认的资源拥有者标识符
         */
        const val DEFAULT_OWNER_ID = ""

        /**
         * 扩展函数，用于处理可能为null的String值
         * 如果字符串为null，则返回默认的资源拥有者标识符
         *
         * @return 如果字符串为null，则返回DEFAULT_OWNER_ID，否则返回字符串本身
         */
        fun String?.orDefaultOwnerId(): String {
            return this ?: DEFAULT_OWNER_ID
        }
    }
}
