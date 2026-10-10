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

package me.ahoo.wow.rest

/** The names of the path variables of Wow's built-in routes, as they appear between braces in a route path. */
object RouteVariables {
    const val ID = "id"
    const val TENANT_ID = "tenantId"
    const val OWNER_ID = "ownerId"
    const val VERSION = "version"
    const val CREATE_TIME = "createTime"

    const val HEAD_VERSION = "headVersion"
    const val TAIL_VERSION = "tailVersion"

    const val BATCH_AFTER_ID = "afterId"
    const val BATCH_LIMIT = "limit"
}
