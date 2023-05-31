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

import me.ahoo.wow.api.naming.Materialized

interface ErrorInfo {
    val succeeded: Boolean get() = SUCCEEDED == errorCode

    val errorCode: String

    val errorMsg: String

    companion object {
        const val SUCCEEDED = "Ok"
        const val SUCCEEDED_MESSAGE = "Ok"
        val OK = DefaultErrorInfo(SUCCEEDED, SUCCEEDED_MESSAGE)
        fun ErrorInfo.materialize(): ErrorInfo {
            if (this is Materialized) {
                return this
            }
            return DefaultErrorInfo(errorCode = errorCode, errorMsg = errorMsg)
        }

        fun of(errorCode: String, errorMsg: String?): ErrorInfo = DefaultErrorInfo(errorCode, errorMsg ?: "")
    }
}

data class DefaultErrorInfo(override val errorCode: String, override val errorMsg: String) : ErrorInfo, Materialized
