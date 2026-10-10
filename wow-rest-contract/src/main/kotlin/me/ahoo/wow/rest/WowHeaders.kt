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

/** The headers any Wow route may read or write, whatever resource it serves. */
object WowHeaders {
    /** The response header carrying the error code of the result, `Ok` on success. */
    const val ERROR_CODE = "Wow-Error-Code"

    /** The request header naming the aggregate space a request addresses. */
    const val SPACE_ID = "Wow-Space-Id"
}
