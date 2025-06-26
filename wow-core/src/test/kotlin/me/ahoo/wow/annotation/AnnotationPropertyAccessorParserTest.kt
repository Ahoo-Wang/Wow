package me.ahoo.wow.annotation

import me.ahoo.test.asserts.assert
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
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class AnnotationPropertyAccessorParserTest {
    @Test
    fun toStringGetter() {
        Mock::stringField.toStringGetter().assert().isNotNull()
    }

    @Test
    fun toStringGetterIfInt() {
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            Mock::intField.toStringGetter()
        }
    }

    @Test
    fun toIntGetter() {
        val propertyGetter = Mock::intField.toIntGetter()
        propertyGetter.assert().isNotNull()
    }

    @Test
    fun toIntGetterIfString() {
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            Mock::stringField.toIntGetter()
        }
    }

    @Test
    fun toAggregateNameGetterIfAnnotated() {
        val propertyGetter = Mock::aggregateName.toAggregateNameGetterIfAnnotated()
        propertyGetter.assert().isNotNull()
    }

    @Test
    fun toAggregateNameGetterIfUnAnnotated() {
        val propertyGetter = Mock::aggregateId.toAggregateNameGetterIfAnnotated()
        propertyGetter.assert().isNull()
    }

    @Test
    fun toAggregateIdGetterIfAnnotated() {
        val propertyGetter = Mock::aggregateId.toAggregateIdGetterIfAnnotated()
        propertyGetter.assert().isNotNull()
    }

    @Test
    fun toAggregateIdGetterIfUnAnnotated() {
        val propertyGetter = Mock::aggregateName.toAggregateIdGetterIfAnnotated()
        propertyGetter.assert().isNull()
    }

    @Test
    fun toStaticAggregateIdGetterIfAnnotated() {
        val propertyGetter = staticAggregateIdGetterIfAnnotated<Mock>()
                propertyGetter.assert().isNotNull()
    }

    @Test
    fun toTenantIdGetterIfAnnotated() {
        val propertyGetter = Mock::tenantId.toTenantIdGetterIfAnnotated()
                propertyGetter.assert().isNotNull()
    }

    @Test
    fun toTenantIdGetterIfUnAnnotated() {
        val propertyGetter = Mock::aggregateId.toTenantIdGetterIfAnnotated()
        propertyGetter.assert().isNull()
    }

    @Test
    fun toAggregateVersionGetterIfAnnotated() {
        val propertyGetter = Mock::aggregateVersion.toAggregateVersionGetterIfAnnotated()
                propertyGetter.assert().isNotNull()
    }

    @Test
    fun toAggregateVersionGetterIfUnAnnotated() {
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
