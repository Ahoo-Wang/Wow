package me.ahoo.wow.annotation

import me.ahoo.wow.annotation.AggregateAnnotationParser.asAggregateIdGetterIfAnnotated
import me.ahoo.wow.annotation.AggregateAnnotationParser.asAggregateNameGetterIfAnnotated
import me.ahoo.wow.annotation.AggregateAnnotationParser.asAggregateVersionGetterIfAnnotated
import me.ahoo.wow.annotation.AggregateAnnotationParser.asIntGetter
import me.ahoo.wow.annotation.AggregateAnnotationParser.asStringGetter
import me.ahoo.wow.annotation.AggregateAnnotationParser.asTenantIdGetterIfAnnotated
import me.ahoo.wow.annotation.AggregateAnnotationParser.staticAggregateIdGetterIfAnnotated
import me.ahoo.wow.annotation.AggregateAnnotationParser.staticTenantIdGetterIfAnnotated
import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.AggregateName
import me.ahoo.wow.api.annotation.AggregateVersion
import me.ahoo.wow.api.annotation.StaticAggregateId
import me.ahoo.wow.api.annotation.StaticTenantId
import me.ahoo.wow.api.annotation.TenantId
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaMethod

class AggregateAnnotationParserTest {

    @Test
    fun fieldAsStringGetter() {
        val propertyGetter = Mock::stringField.javaField!!.asStringGetter<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun fieldAsStringGetterIfInt() {
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            Mock::intField.javaField!!.asStringGetter<Mock>()
        }
    }

    @Test
    fun methodAsStringGetter() {
        val propertyGetter = Mock::stringField.getter.javaMethod!!.asStringGetter<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun methodAsStringGetterIfInt() {
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            Mock::intField.getter.javaMethod!!.asStringGetter<Mock>()
        }
    }

    @Test
    fun fieldAsIntGetter() {
        val propertyGetter = Mock::intField.javaField!!.asIntGetter<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun fieldAsIntGetterIfString() {
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            Mock::stringField.javaField!!.asIntGetter<Mock>()
        }
    }

    @Test
    fun methodAsIntGetter() {
        val propertyGetter = Mock::intField.getter.javaMethod!!.asIntGetter<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun methodAsIntGetterIfString() {
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            Mock::stringField.getter.javaMethod!!.asIntGetter<Mock>()
        }
    }

    @Test
    fun fieldAsAggregateNameGetterIfAnnotated() {
        val propertyGetter = Mock::aggregateName.javaField!!.asAggregateNameGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun fieldAsAggregateNameGetterIfUnAnnotated() {
        val propertyGetter = Mock::aggregateId.javaField!!.asAggregateNameGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, nullValue())
    }

    @Test
    fun methodAsAggregateNameGetterIfAnnotated() {
        val propertyGetter = Mock::aggregateName.getter.javaMethod!!.asAggregateNameGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun methodAsAggregateNameGetterIfUnAnnotated() {
        val propertyGetter = Mock::aggregateId.getter.javaMethod!!.asAggregateNameGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, nullValue())
    }

    @Test
    fun fieldAsAggregateIdGetterIfAnnotated() {
        val propertyGetter = Mock::aggregateId.javaField!!.asAggregateIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun fieldAsAggregateIdGetterIfUnAnnotated() {
        val propertyGetter = Mock::aggregateName.javaField!!.asAggregateIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, nullValue())
    }

    @Test
    fun methodsAggregateIdGetterIfAnnotated() {
        val propertyGetter = Mock::aggregateId.getter.javaMethod!!.asAggregateIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun methodsAggregateIdGetterIfUnAnnotated() {
        val propertyGetter = Mock::aggregateName.getter.javaMethod!!.asAggregateIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, nullValue())
    }

    @Test
    fun classAsStaticAggregateIdGetterIfAnnotated() {
        val propertyGetter = staticAggregateIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun fieldAsTenantIdGetterIfAnnotated() {
        val propertyGetter = Mock::tenantId.javaField!!.asTenantIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun fieldAsTenantIdGetterIfUnAnnotated() {
        val propertyGetter = Mock::aggregateId.javaField!!.asTenantIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, nullValue())
    }

    @Test
    fun classAsStaticTenantIdGetterIfAnnotated() {
        val propertyGetter = staticTenantIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun methodAsTenantIdGetterIfAnnotated() {
        val propertyGetter = Mock::tenantId.getter.javaMethod!!.asTenantIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun methodAsTenantIdGetterIfUnAnnotated() {
        val propertyGetter = Mock::aggregateId.getter.javaMethod!!.asTenantIdGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, nullValue())
    }

    @Test
    fun fieldAsAggregateVersionGetterIfAnnotated() {
        val propertyGetter = Mock::aggregateVersion.javaField!!.asAggregateVersionGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun fieldAsAggregateVersionGetterIfUnAnnotated() {
        val propertyGetter = Mock::aggregateId.javaField!!.asAggregateVersionGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, nullValue())
    }

    @Test
    fun methodAsAggregateVersionGetterIfAnnotated() {
        val propertyGetter = Mock::aggregateVersion.getter.javaMethod!!.asAggregateVersionGetterIfAnnotated<Mock>()
        assertThat(propertyGetter, notNullValue())
    }

    @Test
    fun methodAsAggregateVersionGetterIfUnAnnotated() {
        val propertyGetter = Mock::aggregateId.getter.javaMethod!!.asAggregateVersionGetterIfAnnotated<Mock>()
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
