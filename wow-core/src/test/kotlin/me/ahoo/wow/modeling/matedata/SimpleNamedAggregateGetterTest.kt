package me.ahoo.wow.modeling.matedata

import me.ahoo.test.asserts.assert
import me.ahoo.wow.infra.accessor.property.StaticPropertyGetter
import org.junit.jupiter.api.Test

class SimpleNamedAggregateGetterTest {

    @Test
    fun getNamedAggregate() {
        val aggregateNameGetter = StaticPropertyGetter<Any, String>("test")
        val getter = SimpleNamedAggregateGetter("test", aggregateNameGetter)
        val namedAggregate = getter.getNamedAggregate(Any())
        namedAggregate.contextName.assert().isEqualTo("test")
        namedAggregate.aggregateName.assert().isEqualTo("test")
    }

    @Test
    fun toNamedAggregateGetter() {
        val getter = StaticPropertyGetter<Any, String>("test")
            .toNamedAggregateGetter(Any::class.java)
        getter.assert().isInstanceOf(NamedAggregateGetter::class.java)
    }
}
