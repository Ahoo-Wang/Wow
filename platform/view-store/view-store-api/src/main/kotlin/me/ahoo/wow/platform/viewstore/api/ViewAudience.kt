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

package me.ahoo.wow.platform.viewstore.api

import com.fasterxml.jackson.annotation.JsonValue
import me.ahoo.wow.platform.viewstore.api.ViewStoreContext.SHARED_OWNER_ID
import me.ahoo.wow.platform.viewstore.api.ViewStoreContext.SYSTEM_OWNER_ID

/**
 * Who a saved view is for. It follows the view's owner: [SHARED_OWNER_ID] is shared, [SYSTEM_OWNER_ID] a stored
 * system view, any other owner personal. A system view never moves audience.
 */
enum class ViewAudience(@get:JsonValue val value: String) {
    PERSONAL("personal"),
    SHARED("shared"),
    SYSTEM("system"),
    ;

    companion object {
        /** The audience of a view owned by [ownerId]. */
        fun ofOwner(ownerId: String): ViewAudience = when (ownerId) {
            SHARED_OWNER_ID -> SHARED
            SYSTEM_OWNER_ID -> SYSTEM
            else -> PERSONAL
        }
    }
}
