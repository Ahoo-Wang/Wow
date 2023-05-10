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

interface ErrorInfo {
    val succeeded: Boolean get() = ErrorCodes.SUCCEEDED == errorCode

    /**
     * Spec: App-Component-Code
     *
     * Core: Component-Code
     *
     * Such as : `USER-LOGIN-404`
     *
     * Code: Try to use common exception codes, such as HTTPStatus
     */
    val errorCode: String

    /**
     * Spec: because/reason --- document link – Solutions
     *
     * Such as :
     *
     * Failed to log in system with email and password(Email login failed): can not find account with email {}
     * --- Please refer https://example.com/login/byemail
     * --- Solutions: 1. check your email  2. check your password
     */
    val errorMsg: String

    companion object {
        fun of(errorCode: String, errorMsg: String): ErrorInfo = DefaultErrorInfo(errorCode, errorMsg)
        fun of(throwable: Throwable): ErrorInfo = DefaultErrorInfo(throwable.errorCode, throwable.message ?: "")
    }
}

data class DefaultErrorInfo(override val errorCode: String, override val errorMsg: String) : ErrorInfo

fun Throwable.asErrorInfo(): ErrorInfo {
    return ErrorInfo.of(this)
}
