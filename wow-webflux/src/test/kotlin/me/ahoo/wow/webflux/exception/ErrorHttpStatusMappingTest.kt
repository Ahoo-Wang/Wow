package me.ahoo.wow.webflux.exception

import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.exception.toErrorInfo
import me.ahoo.wow.webflux.exception.ErrorHttpStatusMapping.toHttpStatus
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class ErrorHttpStatusMappingTest {

    @Test
    fun register() {
        ErrorHttpStatusMapping.register("register", HttpStatus.BAD_REQUEST)
        assertThat(ErrorHttpStatusMapping.getHttpStatus("register"), equalTo(HttpStatus.BAD_REQUEST))
    }

    @Test
    fun unregister() {
        ErrorHttpStatusMapping.register("unregister", HttpStatus.BAD_REQUEST)
        assertThat(ErrorHttpStatusMapping.getHttpStatus("unregister"), equalTo(HttpStatus.BAD_REQUEST))
        ErrorHttpStatusMapping.unregister("unregister")
        assertThat(ErrorHttpStatusMapping.getHttpStatus("unregister"), nullValue())
    }

    @Test
    fun toHttpStatus() {
        IllegalArgumentException().toErrorInfo().toHttpStatus().let {
            assertThat(it, equalTo(HttpStatus.BAD_REQUEST))
        }
    }

    @Test
    fun toHttpStatusIfMissing() {
        ErrorInfo.of("asHttpStatusIfMissing", "").toHttpStatus().let {
            assertThat(it, equalTo(HttpStatus.BAD_REQUEST))
        }
    }
}
