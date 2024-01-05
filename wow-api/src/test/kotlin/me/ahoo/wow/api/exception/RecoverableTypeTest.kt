package me.ahoo.wow.api.exception

import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class RecoverableTypeTest {
    @Test
    fun first() {
        RecoverableType.first(RecoverableType.RECOVERABLE, RecoverableType.UNRECOVERABLE).let {
            assertThat(it, equalTo(RecoverableType.RECOVERABLE))
        }
        RecoverableType.first(RecoverableType.UNKNOWN, RecoverableType.UNRECOVERABLE).let {
            assertThat(it, equalTo(RecoverableType.UNRECOVERABLE))
        }
        RecoverableType.first(null, RecoverableType.UNRECOVERABLE).let {
            assertThat(it, equalTo(RecoverableType.UNRECOVERABLE))
        }
        RecoverableType.first(null, null).let {
            assertThat(it, equalTo(RecoverableType.UNKNOWN))
        }
        RecoverableType.first(null, RecoverableType.UNKNOWN).let {
            assertThat(it, equalTo(RecoverableType.UNKNOWN))
        }
    }
}
