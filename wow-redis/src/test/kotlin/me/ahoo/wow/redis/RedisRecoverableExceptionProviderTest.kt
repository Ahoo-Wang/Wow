package me.ahoo.wow.redis

import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisCommandTimeoutException
import io.lettuce.core.RedisConnectionException
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.RecoverableType
import me.ahoo.wow.exception.recoverable
import org.junit.jupiter.api.Test
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.RedisSystemException

class RedisRecoverableExceptionProviderTest {

    @Test
    fun `lost connections and timeouts are recoverable through the service loader`() {
        RedisConnectionFailureException("refused").recoverable.assert().isEqualTo(RecoverableType.RECOVERABLE)
        RedisConnectionException("reset").recoverable.assert().isEqualTo(RecoverableType.RECOVERABLE)
        RedisCommandTimeoutException("timeout").recoverable.assert().isEqualTo(RecoverableType.RECOVERABLE)
        QueryTimeoutException("timeout").recoverable.assert().isEqualTo(RecoverableType.RECOVERABLE)
    }

    @Test
    fun `command errors stay unknown`() {
        RedisCommandExecutionException("WRONGTYPE").recoverable.assert().isEqualTo(RecoverableType.UNKNOWN)
        RedisSystemException("error", RuntimeException()).recoverable.assert().isEqualTo(RecoverableType.UNKNOWN)
    }
}
