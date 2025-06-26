package me.ahoo.wow.command.factory

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.factory.CommandBuilder.Companion.commandBuilder
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.isLocalFirst
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

class MutableCommandBuilderTest {
    @Test
    fun default() {
        val commandBuilder = this.commandBuilder()
        commandBuilder.body.assert().isSameAs(this)
        commandBuilder.id.assert().isNotNull()
        commandBuilder.requestId.assert().isNull()
        commandBuilder.aggregateId.assert().isNull()
        commandBuilder.tenantId.assert().isNull()
        commandBuilder.ownerId.assert().isNull()
        commandBuilder.aggregateVersion.assert().isNull()
        commandBuilder.namedAggregate.assert().isNull()
        commandBuilder.header.assert().isEqualTo(DefaultHeader.empty())
        commandBuilder.createTime.assert().isCloseTo(System.currentTimeMillis(), Offset.offset(5000))
    }

    @Test
    fun customize() {
        val newBody = Any()
        val commandBuilder = this.commandBuilder()
            .id("id")
            .requestIdIfAbsent("requestId")
            .aggregateId("aggregateId")
            .tenantIdIfAbsent("tenantId")
            .ownerId("ownerId")
            .aggregateVersion(1)
            .namedAggregate(MOCK_AGGREGATE_METADATA)
            .header {
                it.with("key", "value")
            }
            .localFirst(true)
            .localFirst()
            .createTime(1)
            .body(newBody)

        commandBuilder.id.assert().isEqualTo("id")
        commandBuilder.requestId.assert().isEqualTo("requestId")
        commandBuilder.aggregateId.assert().isEqualTo("aggregateId")
        commandBuilder.tenantId.assert().isEqualTo("tenantId")
        commandBuilder.ownerId.assert().isEqualTo("ownerId")
        commandBuilder.aggregateVersion.assert().isEqualTo(1)
        commandBuilder.namedAggregate.assert().isEqualTo(MOCK_AGGREGATE_METADATA)
        commandBuilder.header["key"].assert().isEqualTo("value")
        commandBuilder.createTime.assert().isEqualTo(1)
        commandBuilder.header.isLocalFirst().assert().isEqualTo(true)
        commandBuilder.requestIdIfAbsent("requestId2")
            .tenantIdIfAbsent("tenantId2")

        commandBuilder.requestId.assert().isEqualTo("requestId")
        commandBuilder.tenantId.assert().isEqualTo("tenantId")
        commandBuilder.bodyAs<Any>().assert().isSameAs(newBody)

        commandBuilder.header(DefaultHeader.empty())
        commandBuilder.header.assert().isEqualTo(DefaultHeader.empty())
    }
}
