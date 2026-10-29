/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.kernel

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/** The full types the generated code builds without reflection, and the lookup of a dependency by one. */
class TypesTest {
    interface Catalog<V> {
        fun of(id: String): V
    }

    class Outer {
        interface Inner<V>
    }

    @Suppress("unused")
    private class Declared(
        val prices: Catalog<String>,
        val nested: Catalog<Map<String, Int>>,
        val covariant: Catalog<out Number>,
        val contravariant: Catalog<in Number>,
        val star: Catalog<*>,
        val inner: Outer.Inner<String>,
        val array: Array<Catalog<String>>,
    )

    private val catalogName: String = Catalog::class.java.typeName
    private val prices: Type = Types.parameterized(Catalog::class.java, String::class.java)

    /** The JDK's type of the field [name] of [Declared], as reflection reads it: what a container matches. */
    private fun declared(name: String): Type = Declared::class.java.getDeclaredField(name).genericType

    @Test
    fun `a built type should equal the JDK's of the same declaration`() {
        prices.assert().isEqualTo(declared("prices"))
        prices.hashCode().assert().isEqualTo(declared("prices").hashCode())
        declared("prices").assert().isEqualTo(prices)
        val nested = Types.parameterized(
            Catalog::class.java,
            Types.parameterized(Map::class.java, String::class.java, Integer::class.java),
        )
        nested.assert().isEqualTo(declared("nested"))
        nested.hashCode().assert().isEqualTo(declared("nested").hashCode())
        Types.parameterized(Catalog::class.java, Types.subtypeOf(Number::class.java)).assert()
            .isEqualTo(declared("covariant"))
        Types.parameterized(Catalog::class.java, Types.supertypeOf(Number::class.java)).assert()
            .isEqualTo(declared("contravariant"))
        val star = Types.parameterized(Catalog::class.java, Types.unbounded())
        star.assert().isEqualTo(declared("star"))
        star.hashCode().assert().isEqualTo(declared("star").hashCode())
        val inner = Types.parameterized(Outer.Inner::class.java, String::class.java)
        inner.assert().isEqualTo(declared("inner"))
        inner.ownerType.assert().isEqualTo(Outer::class.java)
    }

    @Test
    fun `a built type should equal no other type`() {
        val covariant = Types.subtypeOf(Number::class.java)
        covariant.assert().isEqualTo((declared("covariant") as ParameterizedType).actualTypeArguments[0])
        covariant.assert().isEqualTo(covariant).isNotEqualTo(Types.subtypeOf(Integer::class.java))
            .isNotEqualTo(Types.supertypeOf(Number::class.java)).isNotEqualTo(Number::class.java)
        prices.assert().isEqualTo(prices).isNotEqualTo(Catalog::class.java)
            .isNotEqualTo(Types.parameterized(Catalog::class.java, Integer::class.java))
            .isNotEqualTo(Types.parameterized(Outer.Inner::class.java, String::class.java))
    }

    @Test
    fun `the class of an array, a type variable or another type should be found as well`() {
        Types.rawOf(declared("array")).assert()
            .isEqualTo(java.lang.reflect.Array.newInstance(Catalog::class.java, 0).javaClass)
        Types.rawOf(Catalog::class.java.typeParameters.single()).assert().isEqualTo(Any::class.java)
        Types.rawOf(object : Type {}).assert().isEqualTo(Any::class.java)
        Types.nameOf(prices).assert().isEqualTo("$catalogName<java.lang.String>")
    }

    @Test
    fun `a built type should name itself as the JDK does, and give its class`() {
        val nested = Types.parameterized(
            Catalog::class.java,
            Types.parameterized(Map::class.java, String::class.java, Types.supertypeOf(Integer::class.java)),
        )
        nested.toString().assert().isEqualTo("$catalogName<java.util.Map<java.lang.String, ? super java.lang.Integer>>")
        Types.parameterized(Catalog::class.java, Types.unbounded()).typeName.assert().isEqualTo("$catalogName<?>")
        Types.subtypeOf(Number::class.java).typeName.assert().isEqualTo("? extends java.lang.Number")
        Types.rawOf(nested).assert().isEqualTo(Catalog::class.java)
        Types.rawOf(String::class.java).assert().isEqualTo(String::class.java)
        Types.rawOf(Types.subtypeOf(Number::class.java)).assert().isEqualTo(Number::class.java)
        Types.rawOf(Types.supertypeOf(Number::class.java)).assert().isEqualTo(Any::class.java)
        Types.nameOf(String::class.java).assert().isEqualTo("java.lang.String")
        assertThrownBy<IllegalArgumentException> { Types.parameterized(Catalog::class.java) }
    }

    @Test
    fun `a host that knows no generics should find a parameterized dependency by its class`() {
        val catalog = object : Catalog<String> {
            override fun of(id: String): String = id
        }
        val dependencies = Dependencies.of(catalog)
        dependencies.find(prices, null).assert().isSameAs(catalog)
        dependencies.get(Catalog::class.java, prices, null, "the Cart model", "prices").assert().isSameAs(catalog)
    }

    @Test
    fun `a missing parameterized dependency should be named by its full type`() {
        assertThrownBy<MissingDependencyException> {
            Dependencies.NONE.get(Catalog::class.java, prices, null, "the Cart model", "prices")
        }.hasMessage(
            "No dependency of $catalogName<java.lang.String> to build the Cart model with: register one with the host.",
        )
    }

    @Test
    fun `an ambiguous parameterized dependency should name the parameter, its type and the candidates`() {
        val ambiguous = object : Dependencies {
            override fun find(type: Class<*>, qualifier: String?): Any? = null

            override fun find(type: Type, qualifier: String?): Any? =
                throw AmbiguousDependencyException(type, listOf("retail", "wholesale"))
        }
        val failure = runCatching { ambiguous.get(Catalog::class.java, prices, null, "the Cart model", "prices") }
            .exceptionOrNull() as AmbiguousDependencyException
        failure.message.assert().isEqualTo(
            "2 dependencies are $catalogName<java.lang.String> (retail, wholesale) and none is primary: the " +
                "parameter prices of the Cart model takes one; qualify it with @Name.",
        )
        failure.parameter.assert().isEqualTo("prices")
        failure.building.assert().isEqualTo("the Cart model")
        failure.candidates.assert().containsExactly("retail", "wholesale")
        (failure.cause as AmbiguousDependencyException).parameter.assert().isNull()
    }

    @Test
    fun `a dependency of another class than the parameter's should fail`() {
        val wrong = object : Dependencies {
            override fun find(type: Class<*>, qualifier: String?): Any? = null

            override fun find(type: Type, qualifier: String?): Any? = "text"
        }
        assertThrownBy<IllegalStateException> {
            wrong.get(Catalog::class.java, prices, null, null, "prices")
        }.hasMessageContaining("is a java.lang.String")
    }
}
