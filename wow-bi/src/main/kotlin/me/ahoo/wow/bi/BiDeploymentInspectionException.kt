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

package me.ahoo.wow.bi

import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.exception.ErrorInfoCapable

sealed class BiDeploymentInspectionException(
    errorCode: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause), ErrorInfoCapable {
    override val errorInfo: ErrorInfo = ErrorInfo.of(errorCode, message)

    class Inconsistent(
        message: String,
        cause: Throwable? = null,
    ) : BiDeploymentInspectionException(INCONSISTENT_ERROR_CODE, message, cause)

    class Unavailable(
        message: String = "BI deployment inspection is unavailable",
        cause: Throwable? = null,
    ) : BiDeploymentInspectionException(UNAVAILABLE_ERROR_CODE, message, cause)

    class Timeout(
        message: String = "BI deployment inspection timed out",
        cause: Throwable? = null,
    ) : BiDeploymentInspectionException(TIMEOUT_ERROR_CODE, message, cause)

    companion object {
        const val INCONSISTENT_ERROR_CODE: String = "BiDeploymentInspectionInconsistent"
        const val UNAVAILABLE_ERROR_CODE: String = "BiDeploymentInspectionUnavailable"
        const val TIMEOUT_ERROR_CODE: String = "BiDeploymentInspectionTimeout"
    }
}
