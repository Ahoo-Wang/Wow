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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.ErrorInfo.Companion.isFailed
import me.ahoo.wow.api.exception.ErrorInfo.Companion.materialize
import me.ahoo.wow.api.exception.ErrorInfo.Companion.toDefault
import org.junit.jupiter.api.Test

class ErrorInfoTest {

    @Test
    fun `should OK have succeeded as true`() {
        ErrorInfo.OK.succeeded.assert().isTrue()
    }

    @Test
    fun `should OK have errorCode as Ok`() {
        ErrorInfo.OK.errorCode.assert().isEqualTo("Ok")
    }

    @Test
    fun `should of with errorCode create error with code and empty message`() {
        val error = ErrorInfo.of("ERROR")
        error.errorCode.assert().isEqualTo("ERROR")
        error.errorMsg.assert().isEmpty()
        error.bindingErrors.assert().isEmpty()
    }

    @Test
    fun `should of with errorCode and message create error with code and message`() {
        val error = ErrorInfo.of("ERROR", "msg")
        error.errorCode.assert().isEqualTo("ERROR")
        error.errorMsg.assert().isEqualTo("msg")
        error.bindingErrors.assert().isEmpty()
    }

    @Test
    fun `should of with bindingErrors create error with binding errors`() {
        val bindingErrors = listOf(BindingError("field", "invalid"))
        val error = ErrorInfo.of("ERROR", "msg", bindingErrors)
        error.errorCode.assert().isEqualTo("ERROR")
        error.errorMsg.assert().isEqualTo("msg")
        error.bindingErrors.assert().hasSize(1)
        error.bindingErrors[0].name.assert().isEqualTo("field")
        error.bindingErrors[0].msg.assert().isEqualTo("invalid")
    }

    @Test
    fun `should succeeded return false for non-Ok error codes`() {
        val error = ErrorInfo.of("VALIDATION_ERROR", "validation failed")
        error.succeeded.assert().isFalse()
    }

    @Test
    fun `should materialize return same instance if already Materialized`() {
        val error = ErrorInfo.OK // DefaultErrorInfo implements Materialized
        val materialized = error.materialize()
        materialized.assert().isSameAs(error)
    }

    @Test
    fun `should materialize create DefaultErrorInfo for non-Materialized`() {
        val nonMaterialized = object : ErrorInfo {
            override val errorCode = "CUSTOM_ERROR"
            override val errorMsg = "custom message"
        }
        val materialized = nonMaterialized.materialize()
        materialized.assert().isInstanceOf(DefaultErrorInfo::class.java)
        materialized.errorCode.assert().isEqualTo("CUSTOM_ERROR")
        materialized.errorMsg.assert().isEqualTo("custom message")
    }

    @Test
    fun `should toDefault return same instance if already DefaultErrorInfo`() {
        val error = ErrorInfo.OK
        val default = error.toDefault()
        default.assert().isSameAs(error)
    }

    @Test
    fun `should isFailed return true for failed ErrorInfo`() {
        val error = ErrorInfo.of("ERROR", "something went wrong")
        error.isFailed().assert().isTrue()
    }

    @Test
    fun `should isFailed return false for null`() {
        val result: Any? = null
        result.isFailed().assert().isFalse()
    }

    @Test
    fun `should isFailed return false for non-ErrorInfo`() {
        val result: Any? = "not an error info"
        result.isFailed().assert().isFalse()
    }

    @Test
    fun `should isFailed return false for OK ErrorInfo`() {
        ErrorInfo.OK.isFailed().assert().isFalse()
    }
}
