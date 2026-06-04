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
    fun `should register error code to http status mapping`() {
        ErrorHttpStatusMapping.register("register", HttpStatus.BAD_REQUEST)
        ErrorHttpStatusMapping.getHttpStatus("register").assert().isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `should unregister error code mapping`() {
        ErrorHttpStatusMapping.register("unregister", HttpStatus.BAD_REQUEST)
        ErrorHttpStatusMapping.getHttpStatus("unregister").assert().isEqualTo(HttpStatus.BAD_REQUEST)
        ErrorHttpStatusMapping.unregister("unregister")
        ErrorHttpStatusMapping.getHttpStatus("unregister").assert().isNull()
    }

    @Test
    fun `should convert error info to http status`() {
        IllegalArgumentException().toErrorInfo().toHttpStatus().let {
            it.assert().isEqualTo(HttpStatus.BAD_REQUEST)
        }
    }

    @Test
    fun `should return bad request when http status mapping is missing`() {
        ErrorInfo.of("asHttpStatusIfMissing", "").toHttpStatus().let {
            it.assert().isEqualTo(HttpStatus.BAD_REQUEST)
        }
    }

    @Test
    fun `should get http status for valid error code`() {
        val httpStatus = ErrorHttpStatusMapping.getHttpStatus(ErrorCodes.NOT_FOUND)
        httpStatus.assert().isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `should return null for invalid error code`() {
        val httpStatus = ErrorHttpStatusMapping.getHttpStatus("invalidErrorCode")
        httpStatus.assert().isNull()
    }
}
