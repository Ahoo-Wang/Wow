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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.api.exception.BindingError
import me.ahoo.wow.api.exception.RecoverableType
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeoutException

class WowExceptionBehaviorTest {

    @Test
    fun `should expose direct error info values`() {
        val cause = RuntimeException("cause")
        val bindingErrors = listOf(BindingError("name", "required"))
        val exception = WowException("CommandRejected", "command rejected", cause, bindingErrors)

        exception.errorCode.assert().isEqualTo("CommandRejected")
        exception.errorMsg.assert().isEqualTo("command rejected")
        exception.message.assert().isEqualTo("command rejected")
        exception.cause.assert().isSameAs(cause)
        exception.bindingErrors.assert().isSameAs(bindingErrors)
        exception.succeeded.assert().isFalse()
    }

    @Test
    fun `should use first binding error message when runtime message is blank`() {
        val exception = WowException(
            errorCode = ErrorCodes.COMMAND_VALIDATION,
            errorMsg = "",
            bindingErrors = listOf(BindingError("quantity", "must be positive")),
        )

        exception.errorMsg.assert().isEqualTo("must be positive")
        exception.message.assert().isEqualTo("must be positive")
    }

    @Test
    fun `should convert WowException to materialized error info`() {
        val errorInfo = WowException(ErrorCodes.ILLEGAL_STATE, "bad state").toErrorInfo()

        errorInfo.errorCode.assert().isEqualTo(ErrorCodes.ILLEGAL_STATE)
        errorInfo.errorMsg.assert().isEqualTo("bad state")
    }

    @Test
    fun `should use default recoverable classification when retry is null`() {
        val retry: Retry? = null

        retry.recoverable(TimeoutException::class.java).assert().isEqualTo(RecoverableType.RECOVERABLE)
        retry.recoverable(RuntimeException::class.java).assert().isEqualTo(RecoverableType.UNKNOWN)
    }

    @Test
    fun `should classify exact retry recoverable entry`() {
        retryAnnotation("exactRecoverable")
            .recoverable(ExactRetryRecoverableException::class.java)
            .assert().isEqualTo(RecoverableType.RECOVERABLE)
    }

    @Test
    fun `should classify exact retry unrecoverable entry`() {
        retryAnnotation("exactUnrecoverable")
            .recoverable(ExactRetryUnrecoverableException::class.java)
            .assert().isEqualTo(RecoverableType.UNRECOVERABLE)
    }

    @Test
    fun `should classify retry recoverable superclass entry`() {
        retryAnnotation("superclassRecoverable")
            .recoverable(SuperclassRetryRecoverableLeafException::class.java)
            .assert().isEqualTo(RecoverableType.RECOVERABLE)
    }

    @Test
    fun `should classify retry unrecoverable superclass entry`() {
        retryAnnotation("superclassUnrecoverable")
            .recoverable(SuperclassRetryUnrecoverableLeafException::class.java)
            .assert().isEqualTo(RecoverableType.UNRECOVERABLE)
    }

    @Test
    fun `should use nearest retry entry and prefer recoverable on equal distance`() {
        retryAnnotation("mixedNearestUnrecoverable")
            .recoverable(MixedRetryLeafException::class.java)
            .assert().isEqualTo(RecoverableType.UNRECOVERABLE)
        retryAnnotation("mixedNearestRecoverable")
            .recoverable(MixedRetryLeafException::class.java)
            .assert().isEqualTo(RecoverableType.RECOVERABLE)
        retryAnnotation("mixedEqualDistance")
            .recoverable(MixedRetryEqualDistanceException::class.java)
            .assert().isEqualTo(RecoverableType.RECOVERABLE)
    }

    private fun retryAnnotation(methodName: String): Retry =
        RetryFixture::class.java.getDeclaredMethod(methodName).getAnnotation(Retry::class.java)
}

private class RetryFixture {
    @Retry(recoverable = [ExactRetryRecoverableException::class])
    fun exactRecoverable() = Unit

    @Retry(unrecoverable = [ExactRetryUnrecoverableException::class])
    fun exactUnrecoverable() = Unit

    @Retry(recoverable = [SuperclassRetryRecoverableException::class])
    fun superclassRecoverable() = Unit

    @Retry(unrecoverable = [SuperclassRetryUnrecoverableException::class])
    fun superclassUnrecoverable() = Unit

    @Retry(
        recoverable = [MixedRetryBaseException::class],
        unrecoverable = [MixedRetryMiddleException::class]
    )
    fun mixedNearestUnrecoverable() = Unit

    @Retry(
        recoverable = [MixedRetryMiddleException::class],
        unrecoverable = [MixedRetryBaseException::class]
    )
    fun mixedNearestRecoverable() = Unit

    @Retry(
        recoverable = [MixedRetryEqualDistanceException::class],
        unrecoverable = [MixedRetryEqualDistanceException::class]
    )
    fun mixedEqualDistance() = Unit
}

private class ExactRetryRecoverableException : RuntimeException()

private class ExactRetryUnrecoverableException : RuntimeException()

private open class SuperclassRetryRecoverableException : RuntimeException()

private class SuperclassRetryRecoverableLeafException : SuperclassRetryRecoverableException()

private open class SuperclassRetryUnrecoverableException : RuntimeException()

private class SuperclassRetryUnrecoverableLeafException : SuperclassRetryUnrecoverableException()

private open class MixedRetryBaseException : RuntimeException()

private open class MixedRetryMiddleException : MixedRetryBaseException()

private class MixedRetryLeafException : MixedRetryMiddleException()

private class MixedRetryEqualDistanceException : RuntimeException()
