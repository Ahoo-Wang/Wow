package me.ahoo.wow.kafka

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.RecoverableType
import me.ahoo.wow.exception.recoverable
import org.apache.kafka.common.errors.AuthorizationException
import org.apache.kafka.common.errors.NotLeaderOrFollowerException
import org.apache.kafka.common.errors.RetriableException
import org.apache.kafka.common.errors.TimeoutException
import org.junit.jupiter.api.Test

class KafkaRecoverableExceptionProviderTest {

    @Test
    fun `Kafka retriable exceptions are recoverable through the service loader`() {
        object : RetriableException("retriable") {}.recoverable.assert().isEqualTo(RecoverableType.RECOVERABLE)
        TimeoutException("request timed out").recoverable.assert().isEqualTo(RecoverableType.RECOVERABLE)
        NotLeaderOrFollowerException("leader moved").recoverable.assert().isEqualTo(RecoverableType.RECOVERABLE)
    }

    @Test
    fun `non-retriable Kafka exceptions stay unknown`() {
        AuthorizationException("denied").recoverable.assert().isEqualTo(RecoverableType.UNKNOWN)
    }
}
