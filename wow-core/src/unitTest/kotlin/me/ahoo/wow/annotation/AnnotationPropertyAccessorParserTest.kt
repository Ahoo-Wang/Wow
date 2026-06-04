package me.ahoo.wow.annotation

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.staticAggregateIdGetterIfAnnotated
import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.toAggregateIdGetterIfAnnotated
import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.toAggregateNameGetterIfAnnotated
import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.toAggregateVersionGetterIfAnnotated
import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.toIntGetter
import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.toStringGetter
import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.toTenantIdGetterIfAnnotated
import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.AggregateName
import me.ahoo.wow.api.annotation.AggregateVersion
import me.ahoo.wow.api.annotation.StaticAggregateId
import me.ahoo.wow.api.annotation.StaticTenantId
import me.ahoo.wow.api.annotation.TenantId
import org.junit.jupiter.api.Test

class AnnotationPropertyAccessorParserTest {
    @Test
    fun `should get string getter from property`() {
        Mock::stringField.toStringGetter().assert().isNotNull()
    }

    @Test
    fun `should throw when getting string getter from int field`() {
        assertThrownBy<IllegalArgumentException> {
            Mock::intField.toStringGetter()
        }
    }

    @Test
    fun `should get int getter from property`() {
        val propertyGetter = Mock::intField.toIntGetter()
        propertyGetter.assert().isNotNull()
    }

    @Test
    fun `should throw when getting int getter from string field`() {
        assertThrownBy<IllegalArgumentException> {
            Mock::stringField.toIntGetter()
        }
    }

    @Test
    fun `should get aggregate name getter when annotated`() {
        val propertyGetter = Mock::aggregateName.toAggregateNameGetterIfAnnotated()
        propertyGetter.assert().isNotNull()
    }

    @Test
    fun `should return null for aggregate name getter when not annotated`() {
        val propertyGetter = Mock::aggregateId.toAggregateNameGetterIfAnnotated()
        propertyGetter.assert().isNull()
    }

    @Test
    fun `should get aggregate id getter when annotated`() {
        val propertyGetter = Mock::aggregateId.toAggregateIdGetterIfAnnotated()
        propertyGetter.assert().isNotNull()
    }

    @Test
    fun `should return null for aggregate id getter when not annotated`() {
        val propertyGetter = Mock::aggregateName.toAggregateIdGetterIfAnnotated()
        propertyGetter.assert().isNull()
    }

    @Test
    fun `should get static aggregate id getter when annotated`() {
        val propertyGetter = staticAggregateIdGetterIfAnnotated<Mock>()
        propertyGetter.assert().isNotNull()
    }

    @Test
    fun `should get tenant id getter when annotated`() {
        val propertyGetter = Mock::tenantId.toTenantIdGetterIfAnnotated()
        propertyGetter.assert().isNotNull()
    }

    @Test
    fun `should return null for tenant id getter when not annotated`() {
        val propertyGetter = Mock::aggregateId.toTenantIdGetterIfAnnotated()
        propertyGetter.assert().isNull()
    }

    @Test
    fun `should get aggregate version getter when annotated`() {
        val propertyGetter = Mock::aggregateVersion.toAggregateVersionGetterIfAnnotated()
        propertyGetter.assert().isNotNull()
    }

    @Test
    fun `should return null for aggregate version getter when not annotated`() {
        val propertyGetter = Mock::aggregateId.toAggregateVersionGetterIfAnnotated()
        propertyGetter.assert().isNull()
    }

    @StaticTenantId
    @StaticAggregateId("staticAggregateId")
    data class Mock(
        val stringField: String,
        val intField: Int,
        @AggregateName
        @get:AggregateName
        val aggregateName: String,
        @AggregateId
        @get:AggregateId
        val aggregateId: String,
        @TenantId
        @get:TenantId
        val tenantId: String,
        @AggregateVersion
        @get:AggregateVersion
        val aggregateVersion: Int
    )
}
