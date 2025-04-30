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
 * 定义一个包含租户ID的属性以及相关操作
 * 租户ID用于在多租户环境中区分不同的租户
 */
interface TenantId {
    /**
     * 获取租户ID
     */
    val tenantId: String

    /**
     * 伴生对象，包含默认租户ID和相关工具函数
     */
    companion object {
        /**
         * 默认租户ID，用于在未指定租户时作为备用
         */
        const val DEFAULT_TENANT_ID = "(0)"

        /**
         * 如果给定的字符串为空，则返回默认租户ID
         *
         * @param this 可能为空的字符串
         * @return 如果给定字符串为空，则返回默认租户ID；否则返回原字符串
         */
        fun String?.orDefaultTenantId(): String {
            return this ?: DEFAULT_TENANT_ID
        }
    }
}
