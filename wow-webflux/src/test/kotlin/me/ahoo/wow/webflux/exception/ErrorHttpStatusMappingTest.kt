package me.ahoo.wow.webflux.exception

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.exception.toErrorInfo
import me.ahoo.wow.webflux.exception.ErrorHttpStatusMapping.toHttpStatus
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class ErrorHttpStatusMappingTest {

    @Test
    fun register() {
        ErrorHttpStatusMapping.register("register", HttpStatus.BAD_REQUEST)
        ErrorHttpStatusMapping.getHttpStatus("register").assert().isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun unregister() {
        ErrorHttpStatusMapping.register("unregister", HttpStatus.BAD_REQUEST)
        ErrorHttpStatusMapping.getHttpStatus("unregister").assert().isEqualTo(HttpStatus.BAD_REQUEST)
        ErrorHttpStatusMapping.unregister("unregister")
        ErrorHttpStatusMapping.getHttpStatus("unregister").assert().isNull()
    }

    @Test
    fun toHttpStatus() {
        IllegalArgumentException().toErrorInfo().toHttpStatus().let {
            it.assert().isEqualTo(HttpStatus.BAD_REQUEST)
        }
    }

    @Test
    fun toHttpStatusIfMissing() {
        ErrorInfo.of("asHttpStatusIfMissing", "").toHttpStatus().let {
            it.assert().isEqualTo(HttpStatus.BAD_REQUEST)
        }
    }

    @Test
    fun getHttpStatus_validErrorCode() {
        val httpStatus = ErrorHttpStatusMapping.getHttpStatus(ErrorCodes.NOT_FOUND)
        httpStatus.assert().isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun getHttpStatus_invalidErrorCode() {
        val httpStatus = ErrorHttpStatusMapping.getHttpStatus("invalidErrorCode")
        httpStatus.assert().isNull()
    }
}
