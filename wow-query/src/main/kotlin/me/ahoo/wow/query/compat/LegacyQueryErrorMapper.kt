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

package me.ahoo.wow.query.compat

import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage

internal class LegacyQueryErrorMapper private constructor() {
    companion object {
        @JvmSynthetic
        internal fun invalidRequest(): Nothing = throw QueryException(
            QueryErrorCode.INVALID_QUERY,
            QueryStage.NORMALIZE,
            QueryErrorReason.INVALID_REQUEST
        )

        @JvmSynthetic
        internal fun resultInvalid(): Nothing = throw QueryException(
            QueryErrorCode.RESULT_VALIDATION_FAILED,
            QueryStage.EXECUTION,
            QueryErrorReason.RESULT_INVALID
        )
    }
}
