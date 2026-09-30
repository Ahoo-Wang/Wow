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

package me.ahoo.wow.viewstore.starter

import me.ahoo.wow.viewstore.api.ViewStoreErrorCodes
import me.ahoo.wow.webflux.exception.ErrorHttpStatusMapping
import org.springframework.http.HttpStatus

/** The HTTP status of each of the view store's error codes. */
object ViewStoreErrorStatuses {
    val STATUSES: Map<String, HttpStatus> = mapOf(
        ViewStoreErrorCodes.VIEW_INVALID to HttpStatus.BAD_REQUEST,
        ViewStoreErrorCodes.VIEW_APP_REQUIRED to HttpStatus.BAD_REQUEST,
        ViewStoreErrorCodes.VIEW_SCOPE_REQUIRED to HttpStatus.BAD_REQUEST,
        ViewStoreErrorCodes.SYSTEM_VIEW_READ_ONLY to HttpStatus.FORBIDDEN,
        ViewStoreErrorCodes.VIEW_EVENT_STREAM_CLOSED to HttpStatus.FORBIDDEN,
    )

    fun register() {
        STATUSES.forEach { (errorCode, status) -> ErrorHttpStatusMapping.register(errorCode, status) }
    }
}
