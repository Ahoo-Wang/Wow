package me.ahoo.wow.webflux.exception

import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.exception.asErrorInfo
import me.ahoo.wow.webflux.exception.ErrorHttpStatusMapping.asHttpStatus
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class ErrorHttpStatusMappingTest {

    @Test
    fun register() {
        ErrorHttpStatusMapping.register("test", HttpStatus.BAD_REQUEST)
        assertThat(ErrorHttpStatusMapping.getHttpStatus("test"), equalTo(HttpStatus.BAD_REQUEST))
    }

    @Test
    fun asHttpStatus() {
        IllegalArgumentException().asErrorInfo().asHttpStatus().let {
            assertThat(it, equalTo(HttpStatus.BAD_REQUEST))
        }
    }

    @Test
    fun asHttpStatusIfMissing() {
        ErrorInfo.of("missing", "").asHttpStatus().let {
            assertThat(it, equalTo(HttpStatus.BAD_REQUEST))
        }
    }
}
