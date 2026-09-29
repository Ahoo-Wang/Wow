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

package me.ahoo.wow.viewstore.domain

import me.ahoo.wow.api.exception.BindingError
import me.ahoo.wow.exception.WowException
import me.ahoo.wow.viewstore.api.ViewStoreErrorCodes

/**
 * A view store rule the request broke, under one of [ViewStoreErrorCodes].
 */
class ViewStoreException(
    errorCode: String,
    errorMsg: String,
    bindingErrors: List<BindingError> = emptyList(),
) : WowException(errorCode = errorCode, errorMsg = errorMsg, bindingErrors = bindingErrors) {
    companion object {
        fun invalid(errorMsg: String, bindingErrors: List<BindingError> = emptyList()): ViewStoreException =
            ViewStoreException(ViewStoreErrorCodes.VIEW_INVALID, errorMsg, bindingErrors)

        fun appRequired(): ViewStoreException =
            ViewStoreException(ViewStoreErrorCodes.VIEW_APP_REQUIRED, "The request carries no CoSec-App-Id.")

        fun operatorRequired(): ViewStoreException = ViewStoreException(
            ViewStoreErrorCodes.VIEW_OPERATOR_REQUIRED,
            "Making a view personal needs an authenticated operator to own it."
        )
    }
}
