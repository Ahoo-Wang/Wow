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

package me.ahoo.wow.exception

import me.ahoo.wow.api.exception.ErrorCodes
import me.ahoo.wow.api.exception.WowException
import me.ahoo.wow.api.exception.errorCode
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

internal class WowExceptionsKtTest {
    @Test
    fun getErrorCode() {
        assertThat(Throwable().errorCode, equalTo(ErrorCodes.UNDEFINED))
        assertThat(IllegalArgumentException().errorCode, equalTo(ErrorCodes.ILLEGAL_ARGUMENT))
        assertThat(IllegalStateException().errorCode, equalTo(ErrorCodes.ILLEGAL_STATE))
        assertThat(WowException("wow-exception", "").errorCode, equalTo("wow-exception"))
    }
}
