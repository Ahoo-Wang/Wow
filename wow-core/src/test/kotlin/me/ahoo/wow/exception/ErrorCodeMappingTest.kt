package me.ahoo.wow.exception

import me.ahoo.wow.exception.ErrorCodeMapping.getErrorCode
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class ErrorCodeMappingTest {

    @Test
    fun register() {
        ErrorCodeMapping.register(CustomExceptionForRegister::class.java, "CUSTOM_EXCEPTION")
        val errorCode = CustomExceptionForRegister().getErrorCode()
        assertThat(errorCode, equalTo("CUSTOM_EXCEPTION"))
        ErrorCodeMapping.unregister(CustomExceptionForRegister::class.java)
        assertThat(CustomExceptionForRegister().getErrorCode(), equalTo(ErrorCodes.BAD_REQUEST))
    }
}

class CustomExceptionForRegister : RuntimeException()
