package me.ahoo.wow.modeling.matedata

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.annotation.MockCommandAggregate
import me.ahoo.wow.modeling.annotation.MockStateAggregate
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateFactory
import org.junit.jupiter.api.Test

class AggregateMetadataTest {

    @Test
    fun testEquals() {
        val aggregateMetadata =
            aggregateMetadata<MockCommandAggregate, MockStateAggregate>()
        aggregateMetadata.equals(aggregateMetadata).assert().isTrue()
        aggregateMetadata.equals(this).assert().isFalse()
        val aggregateMetadata2 =
            aggregateMetadata<MockCommandAggregate, MockStateAggregate>()
        aggregateMetadata2.equals(aggregateMetadata).assert().isTrue()
    }

    @Test
    fun testHashCode() {
        val aggregateMetadata =
            aggregateMetadata<MockCommandAggregate, MockStateAggregate>()
        val aggregateMetadata2 =
            aggregateMetadata<MockCommandAggregate, MockStateAggregate>()
        aggregateMetadata.hashCode().assert().isEqualTo(aggregateMetadata2.hashCode())
    }

    @Test
    fun testToString() {
        val aggregateMetadata =
            aggregateMetadata<MockCommandAggregate, MockStateAggregate>()
        aggregateMetadata.toString().assert().isNotNull()
    }

    @Test
    fun testIsAggregationPattern() {
        val aggregateMetadata =
            aggregateMetadata<MockCommandAggregate, MockStateAggregate>()
        // 当Command和State是不同类型时，isAggregationPattern应该为true
        aggregateMetadata.isAggregationPattern.assert().isTrue()
    }

    @Test
    fun testExtractAggregateIdWithAccessor() {
        val aggregateMetadata =
            aggregateMetadata<MockCommandAggregate, MockStateAggregate>()
        val stateAggregateFactory: StateAggregateFactory = ConstructorStateAggregateFactory
        val aggregateId = aggregateMetadata.aggregateId(
            id = "testId",
            tenantId = TenantId.DEFAULT_TENANT_ID
        )
        val stateAggregate: StateAggregate<MockStateAggregate> =
            stateAggregateFactory.create(aggregateMetadata.state, aggregateId)
        val extractedAggregateId = aggregateMetadata.extractAggregateId(stateAggregate.state, "testId")
        extractedAggregateId.id.assert().isEqualTo("testId")
        extractedAggregateId.tenantId.assert().isEqualTo(TenantId.DEFAULT_TENANT_ID)
    }

    @Test
    fun testExtractAggregateIdWithoutAccessor() {
        val aggregateMetadata =
            aggregateMetadata<MockCommandAggregate, MockStateAggregate>()
        val stateAggregateFactory: StateAggregateFactory = ConstructorStateAggregateFactory

        val aggregateId = aggregateMetadata.aggregateId(
            id = generateGlobalId(),
            tenantId = TenantId.DEFAULT_TENANT_ID
        )
        val stateAggregate: StateAggregate<MockStateAggregate> =
            stateAggregateFactory.create(aggregateMetadata.state, aggregateId)

        // 测试当没有accessor时，使用传入的aggregateId参数
        val extractedAggregateId = aggregateMetadata.extractAggregateId(stateAggregate.state, generateGlobalId())
        extractedAggregateId.id.assert().isEqualTo(aggregateId.id)
    }

    @Test
    fun testExtractAggregateIdWithCustomTenantId() {
        val aggregateMetadata =
            aggregateMetadata<MockCommandAggregate, MockStateAggregate>()
        val stateAggregateFactory: StateAggregateFactory = ConstructorStateAggregateFactory
        val aggregateId = aggregateMetadata.aggregateId(
            id = "testId",
            tenantId = TenantId.DEFAULT_TENANT_ID
        )
        val stateAggregate: StateAggregate<MockStateAggregate> =
            stateAggregateFactory.create(aggregateMetadata.state, aggregateId)

        val customTenantId = "customTenantId"
        val extractedAggregateId = aggregateMetadata.extractAggregateId(stateAggregate.state, "testId", customTenantId)
        extractedAggregateId.id.assert().isEqualTo("testId")
        extractedAggregateId.tenantId.assert().isEqualTo(customTenantId)
    }

    @Test
    fun testAsAggregateMetadata() {
        val namedAggregate = aggregateMetadata<MockCommandAggregate, MockStateAggregate>().namedAggregate
        val converted = namedAggregate.asAggregateMetadata<MockCommandAggregate, MockStateAggregate>()
        converted.assert().isSameAs(aggregateMetadata<MockCommandAggregate, MockStateAggregate>())
    }

    @Test
    fun testAsAggregateMetadataWhenAggregateMetadata() {
        val namedAggregate = aggregateMetadata<MockCommandAggregate, MockStateAggregate>()
        val converted = namedAggregate.asAggregateMetadata<MockCommandAggregate, MockStateAggregate>()
        converted.assert().isSameAs(aggregateMetadata<MockCommandAggregate, MockStateAggregate>())
    }
}
