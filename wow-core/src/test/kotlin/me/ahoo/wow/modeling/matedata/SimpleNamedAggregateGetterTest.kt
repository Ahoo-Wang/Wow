package me.ahoo.wow.modeling.matedata

import me.ahoo.wow.infra.accessor.property.StaticPropertyGetter
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.instanceOf
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

class SimpleNamedAggregateGetterTest {

    @Test
    fun getNamedAggregate() {
        val aggregateNameGetter = StaticPropertyGetter<Any, String>("test")
        val getter = SimpleNamedAggregateGetter<Any>("test", aggregateNameGetter)
        val namedAggregate = getter.getNamedAggregate(Any())
        assertThat(namedAggregate.contextName, equalTo("test"))
        assertThat(namedAggregate.aggregateName, equalTo("test"))
    }

    @Test
    fun toNamedAggregateGetter() {
        val getter = StaticPropertyGetter<Any, String>("test")
            .toNamedAggregateGetter(Any::class.java)
        assertThat(getter, instanceOf(SimpleNamedAggregateGetter::class.java))
    }
}
