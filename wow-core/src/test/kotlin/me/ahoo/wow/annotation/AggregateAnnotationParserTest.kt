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
    fun fieldAsStringGetter() {
        val propertyGetter = Mock::stringField.javaField!!.toStringGetter<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun fieldAsStringGetterIfInt() {
        assertThrows(IllegalArgumentException::class.java) {
            Mock::intField.javaField!!.toStringGetter<Mock>()
        }
    }

    @Test
    fun methodAsStringGetter() {
        val propertyGetter = Mock::stringField.getter.javaMethod!!.toStringGetter<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun methodAsStringGetterIfInt() {
        assertThrows(IllegalArgumentException::class.java) {
            Mock::intField.getter.javaMethod!!.toStringGetter<Mock>()
        }
    }

    @Test
    fun fieldAsIntGetter() {
        val propertyGetter = Mock::intField.javaField!!.toIntGetter<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun fieldAsIntGetterIfString() {
        assertThrows(IllegalArgumentException::class.java) {
            Mock::stringField.javaField!!.toIntGetter<Mock>()
        }
    }

    @Test
    fun methodAsIntGetter() {
        val propertyGetter = Mock::intField.getter.javaMethod!!.toIntGetter<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun methodAsIntGetterIfString() {
        assertThrows(IllegalArgumentException::class.java) {
            Mock::stringField.getter.javaMethod!!.toIntGetter<Mock>()
        }
    }

    @Test
    fun fieldAsAggregateNameGetterIfAnnotated() {
        val propertyGetter = Mock::aggregateName.javaField!!.toAggregateNameGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun fieldAsAggregateNameGetterIfUnAnnotated() {
        val propertyGetter = Mock::aggregateId.javaField!!.toAggregateNameGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, nullValue())
    }

    @Test
    fun methodAsAggregateNameGetterIfAnnotated() {
        val propertyGetter = Mock::aggregateName.getter.javaMethod!!.toAggregateNameGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun methodAsAggregateNameGetterIfUnAnnotated() {
        val propertyGetter = Mock::aggregateId.getter.javaMethod!!.toAggregateNameGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, nullValue())
    }

    @Test
    fun fieldAsAggregateIdGetterIfAnnotated() {
        val propertyGetter = Mock::aggregateId.javaField!!.toAggregateIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun fieldAsAggregateIdGetterIfUnAnnotated() {
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
    fun classAsStaticAggregateIdGetterIfAnnotated() {
        val propertyGetter = staticAggregateIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun fieldAsTenantIdGetterIfAnnotated() {
        val propertyGetter = Mock::tenantId.javaField!!.toTenantIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun fieldAsTenantIdGetterIfUnAnnotated() {
        val propertyGetter = Mock::aggregateId.javaField!!.toTenantIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, nullValue())
    }

    @Test
    fun classAsStaticTenantIdGetterIfAnnotated() {
        val propertyGetter = staticTenantIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun methodAsTenantIdGetterIfAnnotated() {
        val propertyGetter = Mock::tenantId.getter.javaMethod!!.toTenantIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun methodAsTenantIdGetterIfUnAnnotated() {
        val propertyGetter = Mock::aggregateId.getter.javaMethod!!.toTenantIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, nullValue())
    }

    @Test
    fun fieldAsAggregateVersionGetterIfAnnotated() {
        val propertyGetter = Mock::aggregateVersion.javaField!!.toAggregateVersionGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun fieldAsAggregateVersionGetterIfUnAnnotated() {
        val propertyGetter = Mock::aggregateId.javaField!!.toAggregateVersionGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, nullValue())
    }

    @Test
    fun methodAsAggregateVersionGetterIfAnnotated() {
        val propertyGetter = Mock::aggregateVersion.getter.javaMethod!!.toAggregateVersionGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun methodAsAggregateVersionGetterIfUnAnnotated() {
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
