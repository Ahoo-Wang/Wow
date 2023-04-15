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

package me.ahoo.wow.api.exception

/**
 * 参考：https://www.rfc-editor.org/rfc/rfc7231#section-6
 */
object ErrorCodes {
    const val PREFIX = "WOW-"
    const val SUCCEEDED = "0"
    const val SUCCEEDED_MSG = ""
    const val UNDEFINED = "-1"

    const val UNDEFINED_CLIENT_ERROR = "400"
    const val NOT_FOUND = "404"
    const val NOT_FOUND_MESSAGE = "Not Found Resource!"
    const val CONFLICT = "409"
    const val GONE = "410"

    /**
     * Precondition Failed
     */
    const val ILLEGAL_ARGUMENT = "412"

    /**
     * Precondition Required
     */
    const val ILLEGAL_STATE = "428"
}
