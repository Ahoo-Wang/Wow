package me.ahoo.wow.annotation

import me.ahoo.wow.annotation.AggregateAnnotationParser.staticAggregateIdGetterIfAnnotated
import me.ahoo.wow.annotation.AggregateAnnotationParser.staticTenantIdGetterIfAnnotated
import me.ahoo.wow.annotation.AggregateAnnotationParser.toAggregateIdGetterIfAnnotated
import me.ahoo.wow.annotation.AggregateAnnotationParser.toAggregateNameGetterIfAnnotated
import me.ahoo.wow.annotation.AggregateAnnotationParser.toAggregateVersionGetterIfAnnotated
import me.ahoo.wow.annotation.AggregateAnnotationParser.toIntGetter
import me.ahoo.wow.annotation.AggregateAnnotationParser.toStringGetter
import me.ahoo.wow.annotation.AggregateAnnotationParser.toTenantIdGetterIfAnnotated
import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.AggregateName
import me.ahoo.wow.api.annotation.AggregateVersion
import me.ahoo.wow.api.annotation.StaticAggregateId
import me.ahoo.wow.api.annotation.StaticTenantId
import me.ahoo.wow.api.annotation.TenantId
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaMethod

class AggregateAnnotationParserTest {

    @Test
    fun fieldToStringGetter() {
        val propertyGetter = Mock::stringField.javaField!!.toStringGetter<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun fieldToStringGetterIfInt() {
        assertThrows(IllegalArgumentException::class.java) {
            Mock::intField.javaField!!.toStringGetter<Mock>()
        }
    }

    @Test
    fun methodToStringGetter() {
        val propertyGetter = Mock::stringField.getter.javaMethod!!.toStringGetter<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun methodToStringGetterIfInt() {
        assertThrows(IllegalArgumentException::class.java) {
            Mock::intField.getter.javaMethod!!.toStringGetter<Mock>()
        }
    }

    @Test
    fun fieldToIntGetter() {
        val propertyGetter = Mock::intField.javaField!!.toIntGetter<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun fieldToIntGetterIfString() {
        assertThrows(IllegalArgumentException::class.java) {
            Mock::stringField.javaField!!.toIntGetter<Mock>()
        }
    }

    @Test
    fun methodToIntGetter() {
        val propertyGetter = Mock::intField.getter.javaMethod!!.toIntGetter<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun methodToIntGetterIfString() {
        assertThrows(IllegalArgumentException::class.java) {
            Mock::stringField.getter.javaMethod!!.toIntGetter<Mock>()
        }
    }

    @Test
    fun fieldToAggregateNameGetterIfAnnotated() {
        val propertyGetter = Mock::aggregateName.javaField!!.toAggregateNameGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun fieldToAggregateNameGetterIfUnAnnotated() {
        val propertyGetter = Mock::aggregateId.javaField!!.toAggregateNameGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, nullValue())
    }

    @Test
    fun methodToAggregateNameGetterIfAnnotated() {
        val propertyGetter = Mock::aggregateName.getter.javaMethod!!.toAggregateNameGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun methodToAggregateNameGetterIfUnAnnotated() {
        val propertyGetter = Mock::aggregateId.getter.javaMethod!!.toAggregateNameGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, nullValue())
    }

    @Test
    fun fieldToAggregateIdGetterIfAnnotated() {
        val propertyGetter = Mock::aggregateId.javaField!!.toAggregateIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun fieldToAggregateIdGetterIfUnAnnotated() {
        val propertyGetter = Mock::aggregateName.javaField!!.toAggregateIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, nullValue())
    }

    @Test
    fun methodsAggregateIdGetterIfAnnotated() {
        val propertyGetter = Mock::aggregateId.getter.javaMethod!!.toAggregateIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun methodsAggregateIdGetterIfUnAnnotated() {
        val propertyGetter = Mock::aggregateName.getter.javaMethod!!.toAggregateIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, nullValue())
    }

    @Test
    fun classToStaticAggregateIdGetterIfAnnotated() {
        val propertyGetter = staticAggregateIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun fieldToTenantIdGetterIfAnnotated() {
        val propertyGetter = Mock::tenantId.javaField!!.toTenantIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun fieldToTenantIdGetterIfUnAnnotated() {
        val propertyGetter = Mock::aggregateId.javaField!!.toTenantIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, nullValue())
    }

    @Test
    fun classToStaticTenantIdGetterIfAnnotated() {
        val propertyGetter = staticTenantIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun methodToTenantIdGetterIfAnnotated() {
        val propertyGetter = Mock::tenantId.getter.javaMethod!!.toTenantIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun methodToTenantIdGetterIfUnAnnotated() {
        val propertyGetter = Mock::aggregateId.getter.javaMethod!!.toTenantIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, nullValue())
    }

    @Test
    fun fieldToAggregateVersionGetterIfAnnotated() {
        val propertyGetter = Mock::aggregateVersion.javaField!!.toAggregateVersionGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun fieldToAggregateVersionGetterIfUnAnnotated() {
        val propertyGetter = Mock::aggregateId.javaField!!.toAggregateVersionGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, nullValue())
    }

    @Test
    fun methodToAggregateVersionGetterIfAnnotated() {
        val propertyGetter = Mock::aggregateVersion.getter.javaMethod!!.toAggregateVersionGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun methodToAggregateVersionGetterIfUnAnnotated() {
        val propertyGetter = Mock::aggregateId.getter.javaMethod!!.toAggregateVersionGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, nullValue())
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
