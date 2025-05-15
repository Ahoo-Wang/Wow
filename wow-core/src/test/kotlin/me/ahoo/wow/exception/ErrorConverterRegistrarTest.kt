package me.ahoo.wow.exception

import me.ahoo.wow.api.exception.ErrorInfo
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class ErrorInfoConverterRegistrarTest {

    @Test
    fun register() {
        val errorInfo = CustomException().toErrorInfo()
        assertThat(errorInfo.errorCode, equalTo("CUSTOM_EXCEPTION"))
        ErrorConverterRegistrar.unregister(CustomException::class.java)
        assertThat(CustomException().toErrorInfo().errorCode, equalTo(ErrorCodes.BAD_REQUEST))
    }
}

class CustomException : RuntimeException()

object CustomExceptionErrorConverter : ErrorConverter<CustomException> {
    override fun convert(error: CustomException): ErrorInfo {
        return ErrorInfo.of("CUSTOM_EXCEPTION")
    }
}

class CustomExceptionErrorConverterFactory : AbstractErrorConverterFactory<CustomException>() {

    override fun create(): ErrorConverter<CustomException> {
        return CustomExceptionErrorConverter
    }
}
