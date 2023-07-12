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

package me.ahoo.wow.openapi

import me.ahoo.wow.api.annotation.DEFAULT_AGGREGATE_ID_NAME

object RoutePaths {
    const val ID_KEY = DEFAULT_AGGREGATE_ID_NAME

    const val COMPENSATE_HEAD_VERSION_KEY = "headVersion"
    const val COMPENSATE_TAIL_VERSION_KEY = "tailVersion"

    const val BATCH_CURSOR_ID = "cursorId"
    const val BATCH_LIMIT = "limit"
}
