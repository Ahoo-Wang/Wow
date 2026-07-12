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

package me.ahoo.wow.bi.expansion.type

import com.fasterxml.jackson.annotation.JsonProperty
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.bi.expansion.BIAggregateState
import me.ahoo.wow.bi.expansion.Item
import me.ahoo.wow.bi.expansion.LikeLinkString
import me.ahoo.wow.bi.expansion.LikeListItem
import me.ahoo.wow.bi.expansion.LikeMapString
import me.ahoo.wow.example.transfer.domain.AccountState
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaGetter

class JsonPropertyTypeResolverTest {
    @Test
    fun `should preserve recursive Kotlin nullability`() {
        val properties = JsonPropertyTypeResolver.resolve(KotlinFixture::class.java)
            .associateBy { it.serializedName }

        properties.getValue("nullableString").run {
            type.rawClass.assert().isEqualTo(String::class.java)
            type.nullability.assert().isEqualTo(Nullability.NULLABLE)
            origin.assert().isEqualTo(ResolvedTypeOrigin.KOTLIN)
            declaringMember.assert().isEqualTo(KotlinFixture::nullableString.javaGetter)
        }
        properties.getValue("nullableList").type.run {
            rawClass.assert().isEqualTo(List::class.java)
            nullability.assert().isEqualTo(Nullability.NULLABLE)
            arguments.single().run {
                rawClass.assert().isEqualTo(String::class.java)
                nullability.assert().isEqualTo(Nullability.NULLABLE)
            }
        }
        properties.getValue("nullableMap").type.run {
            rawClass.assert().isEqualTo(Map::class.java)
            nullability.assert().isEqualTo(Nullability.NULLABLE)
            arguments[0].run {
                rawClass.assert().isEqualTo(String::class.java)
                nullability.assert().isEqualTo(Nullability.NON_NULL)
            }
            arguments[1].run {
                rawClass.assert().isEqualTo(Int::class.javaObjectType)
                nullability.assert().isEqualTo(Nullability.NULLABLE)
            }
        }
        properties.getValue("nullableChild").type.run {
            rawClass.assert().isEqualTo(Child::class.java)
            nullability.assert().isEqualTo(Nullability.NULLABLE)
        }
        properties.getValue("nullableChildren").type.run {
            rawClass.assert().isEqualTo(List::class.java)
            nullability.assert().isEqualTo(Nullability.NON_NULL)
            arguments.single().run {
                rawClass.assert().isEqualTo(Child::class.java)
                nullability.assert().isEqualTo(Nullability.NULLABLE)
            }
        }
        properties.getValue("boxed").type.run {
            rawClass.assert().isEqualTo(Box::class.java)
            nullability.assert().isEqualTo(Nullability.NON_NULL)
            arguments.single().run {
                rawClass.assert().isEqualTo(List::class.java)
                nullability.assert().isEqualTo(Nullability.NULLABLE)
                arguments.single().run {
                    rawClass.assert().isEqualTo(String::class.java)
                    nullability.assert().isEqualTo(Nullability.NULLABLE)
                }
            }
        }
    }

    @Test
    fun `should preserve nested Kotlin member nullability`() {
        val property = JsonPropertyTypeResolver.resolve(Child::class.java).single()

        property.serializedName.assert().isEqualTo("name")
        property.type.rawClass.assert().isEqualTo(String::class.java)
        property.type.nullability.assert().isEqualTo(Nullability.NON_NULL)
        property.origin.assert().isEqualTo(ResolvedTypeOrigin.KOTLIN)
        property.declaringMember.assert().isEqualTo(Child::name.javaGetter)
    }

    @Test
    fun `should seed nested generic member resolution from parent resolved arguments`() {
        val boxedType = JsonPropertyTypeResolver.resolve(KotlinFixture::class.java)
            .single { it.serializedName == "boxed" }
            .type

        val value = JsonPropertyTypeResolver.resolve(boxedType).single()

        value.serializedName.assert().isEqualTo("value")
        value.type.rawClass.assert().isEqualTo(List::class.java)
        value.type.nullability.assert().isEqualTo(Nullability.NULLABLE)
        value.type.arguments.single().run {
            rawClass.assert().isEqualTo(String::class.java)
            nullability.assert().isEqualTo(Nullability.NULLABLE)
        }
    }

    @Test
    fun `should derive semantic generic shapes for concrete Kotlin collection and map subclasses`() {
        val properties = JsonPropertyTypeResolver.resolve(BIAggregateState::class.java)
            .associateBy { it.serializedName }

        properties.getValue("likeLinkString").type.run {
            rawClass.assert().isEqualTo(LikeLinkString::class.java)
            arguments.single().run {
                rawClass.assert().isEqualTo(String::class.java)
                nullability.assert().isEqualTo(Nullability.NON_NULL)
            }
        }
        properties.getValue("likeListItem").type.run {
            rawClass.assert().isEqualTo(LikeListItem::class.java)
            arguments.single().run {
                rawClass.assert().isEqualTo(Item::class.java)
                nullability.assert().isEqualTo(Nullability.NON_NULL)
            }
        }
        properties.getValue("likeMapString").type.run {
            rawClass.assert().isEqualTo(LikeMapString::class.java)
            arguments[0].run {
                rawClass.assert().isEqualTo(String::class.java)
                nullability.assert().isEqualTo(Nullability.NON_NULL)
            }
            arguments[1].run {
                rawClass.assert().isEqualTo(String::class.java)
                nullability.assert().isEqualTo(Nullability.NON_NULL)
            }
        }
    }

    @Test
    fun `should keep Java concrete container platform arguments unknown`() {
        val properties = JsonPropertyTypeResolver.resolve(JavaPlatformContainerFixture::class.java)
            .associateBy { it.serializedName }

        properties.getValue("platformList").type.arguments.single().run {
            rawClass.assert().isEqualTo(String::class.java)
            nullability.assert().isEqualTo(Nullability.UNKNOWN)
        }
        properties.getValue("platformMap").type.arguments.run {
            get(0).rawClass.assert().isEqualTo(String::class.java)
            get(0).nullability.assert().isEqualTo(Nullability.UNKNOWN)
            get(1).rawClass.assert().isEqualTo(Int::class.javaObjectType)
            get(1).nullability.assert().isEqualTo(Nullability.UNKNOWN)
        }
    }

    @Test
    fun `should substitute inherited Kotlin generic and keep Jackson rename`() {
        val property = JsonPropertyTypeResolver.resolve(StringDerived::class.java).single()
        val inheritedGetter = GenericBase::class.memberProperties
            .single { it.name == "inherited" }
            .javaGetter

        property.serializedName.assert().isEqualTo("renamed")
        property.type.rawClass.assert().isEqualTo(String::class.java)
        property.type.nullability.assert().isEqualTo(Nullability.NULLABLE)
        property.origin.assert().isEqualTo(ResolvedTypeOrigin.KOTLIN)
        property.declaringMember.assert().isEqualTo(inheritedGetter)
    }

    @Test
    fun `should resolve Java declaration and type-use nullability`() {
        val properties = JsonPropertyTypeResolver.resolve(JavaNullabilityFixture::class.java)
            .associateBy { it.serializedName }

        properties.getValue("primitive").type.run {
            rawClass.assert().isEqualTo(Int::class.javaPrimitiveType)
            nullability.assert().isEqualTo(Nullability.NON_NULL)
        }
        properties.getValue("nullableBoxed").type.run {
            rawClass.assert().isEqualTo(Int::class.javaObjectType)
            nullability.assert().isEqualTo(Nullability.NULLABLE)
        }
        properties.getValue("nullableReference").type.nullability.assert()
            .isEqualTo(Nullability.NULLABLE)
        properties.getValue("nonNullFromGetter").type.nullability.assert()
            .isEqualTo(Nullability.NON_NULL)
        properties.getValue("unknownReference").type.nullability.assert()
            .isEqualTo(Nullability.UNKNOWN)
        properties.getValue("nullableElementList").type.run {
            rawClass.assert().isEqualTo(List::class.java)
            nullability.assert().isEqualTo(Nullability.UNKNOWN)
            arguments.single().run {
                rawClass.assert().isEqualTo(String::class.java)
                nullability.assert().isEqualTo(Nullability.NULLABLE)
            }
        }
        properties.values.forEach {
            it.origin.assert().isEqualTo(ResolvedTypeOrigin.JAVA)
        }
        properties.getValue("nonNullFromGetter").declaringMember.assert()
            .isEqualTo(JavaNullabilityFixture::class.java.getMethod("getNonNullFromGetter"))
    }

    @Test
    fun `should keep Java JsonProperty name and accessor identity`() {
        val property = JsonPropertyTypeResolver.resolve(JavaNullabilityFixture::class.java)
            .single { it.serializedName == "renamedJava" }

        property.type.rawClass.assert().isEqualTo(String::class.java)
        property.type.nullability.assert().isEqualTo(Nullability.UNKNOWN)
        property.origin.assert().isEqualTo(ResolvedTypeOrigin.JAVA)
        property.declaringMember.assert()
            .isEqualTo(JavaNullabilityFixture::class.java.getMethod("getOriginalName"))
    }

    @Test
    fun `should inherit non-null contract from Kotlin interface overridden by Java getter`() {
        val properties = JsonPropertyTypeResolver.resolve(AccountState::class.java)
            .associateBy { it.serializedName }

        properties.getValue("id").type.nullability.assert().isEqualTo(Nullability.NON_NULL)
        properties.getValue("name").type.nullability.assert().isEqualTo(Nullability.UNKNOWN)
    }

    @Test
    fun `should inherit recursive generic nullability from Kotlin contract implemented in Java`() {
        val properties = JsonPropertyTypeResolver.resolve(
            JavaNullabilityFixture.KotlinGenericContractState::class.java
        ).associateBy { it.serializedName }

        properties.getValue("nonNullValues").type.run {
            nullability.assert().isEqualTo(Nullability.NON_NULL)
            arguments.single().nullability.assert().isEqualTo(Nullability.NON_NULL)
        }
        properties.getValue("nullableElementValues").type.run {
            nullability.assert().isEqualTo(Nullability.NON_NULL)
            arguments.single().nullability.assert().isEqualTo(Nullability.NULLABLE)
        }
        properties.getValue("unknownKeyValues").type.run {
            nullability.assert().isEqualTo(Nullability.NON_NULL)
            arguments.first().nullability.assert().isEqualTo(Nullability.UNKNOWN)
        }
    }

    @Test
    fun `should keep Java instantiation of Kotlin generic contract unknown`() {
        val property = JsonPropertyTypeResolver.resolve(
            JavaNullabilityFixture.KotlinGenericContractState::class.java
        ).single { it.serializedName == "genericValues" }

        property.origin.assert().isEqualTo(ResolvedTypeOrigin.JAVA)
        property.type.nullability.assert().isEqualTo(Nullability.NON_NULL)
        property.type.arguments.single().nullability.assert().isEqualTo(Nullability.UNKNOWN)
    }

    @Test
    fun `should seed nested Java generic member resolution from parent resolved arguments`() {
        val boxes = JsonPropertyTypeResolver.resolve(JavaNullabilityFixture.GenericBoxState::class.java)
            .associateBy { it.serializedName }

        fun resolveValue(name: String): ResolvedType {
            return JsonPropertyTypeResolver.resolve(boxes.getValue(name).type)
                .single { it.serializedName == "value" }
                .type
        }

        resolveValue("scalarBox").run {
            rawClass.assert().isEqualTo(String::class.java)
            nullability.assert().isEqualTo(Nullability.NON_NULL)
        }
        resolveValue("listBox").run {
            rawClass.assert().isEqualTo(List::class.java)
            nullability.assert().isEqualTo(Nullability.NON_NULL)
            arguments.single().run {
                rawClass.assert().isEqualTo(String::class.java)
                nullability.assert().isEqualTo(Nullability.NULLABLE)
            }
        }
        resolveValue("mapBox").run {
            rawClass.assert().isEqualTo(Map::class.java)
            nullability.assert().isEqualTo(Nullability.NON_NULL)
            arguments[0].run {
                rawClass.assert().isEqualTo(String::class.java)
                nullability.assert().isEqualTo(Nullability.NON_NULL)
            }
            arguments[1].run {
                rawClass.assert().isEqualTo(Int::class.javaObjectType)
                nullability.assert().isEqualTo(Nullability.NON_NULL)
            }
        }
    }

    @Test
    fun `should resolve record component declaration nullability`() {
        val property = JsonPropertyTypeResolver.resolve(
            JavaRecordNullabilityFixture.ComponentOnly::class.java
        ).single()

        property.serializedName.assert().isEqualTo("value")
        property.type.rawClass.assert().isEqualTo(String::class.java)
        property.type.nullability.assert().isEqualTo(Nullability.NULLABLE)
        property.origin.assert().isEqualTo(ResolvedTypeOrigin.JAVA)
        property.declaringMember.assert()
            .isEqualTo(JavaRecordNullabilityFixture.ComponentOnly::class.java.getMethod("value"))
    }

    @Test
    fun `should resolve annotated Java generic superclass arguments`() {
        val value = JsonPropertyTypeResolver.resolve(JavaNullabilityFixture.AnnotatedMapBox::class.java)
            .single { it.serializedName == "value" }
            .type

        value.rawClass.assert().isEqualTo(Map::class.java)
        value.nullability.assert().isEqualTo(Nullability.NON_NULL)
        value.arguments[0].run {
            rawClass.assert().isEqualTo(String::class.java)
            nullability.assert().isEqualTo(Nullability.NON_NULL)
        }
        value.arguments[1].run {
            rawClass.assert().isEqualTo(Int::class.javaObjectType)
            nullability.assert().isEqualTo(Nullability.NON_NULL)
        }
    }

    @Test
    fun `should reject conflicting Java generic diamond contracts`() {
        assertThrownBy<IllegalArgumentException> {
            JsonPropertyTypeResolver.resolve(
                JavaNullabilityFixture.ConflictingGenericDiamond::class.java
            )
        }.hasMessageContaining(JavaNullabilityFixture.ConflictingGenericDiamond::class.java.name)
            .hasMessageContaining("value")
            .hasMessageContaining("Conflicting")
    }

    @Test
    fun `should accept non-null Kotlin contract refinement implemented in Java`() {
        val property = JsonPropertyTypeResolver.resolve(
            JavaNullabilityFixture.RefinedContractImplementation::class.java
        ).single()

        property.serializedName.assert().isEqualTo("refinedValue")
        property.type.nullability.assert().isEqualTo(Nullability.NON_NULL)
    }

    @Test
    fun `should reject conflicting unrelated Kotlin contracts implemented in Java`() {
        assertThrownBy<IllegalArgumentException> {
            JsonPropertyTypeResolver.resolve(
                JavaNullabilityFixture.ConflictingParallelContractImplementation::class.java
            )
        }.hasMessageContaining("parallelValue")
            .hasMessageContaining("Conflicting")
    }

    @Test
    fun `should reject contradictory Java nullability annotations`() {
        assertThrownBy<IllegalArgumentException> {
            JsonPropertyTypeResolver.resolve(JavaNullabilityFixture.ConflictingAnnotations::class.java)
        }.hasMessageContaining(JavaNullabilityFixture.ConflictingAnnotations::class.java.name)
            .hasMessageContaining("conflicting")
    }

    @Test
    fun `should inherit recursive nullability from overridden Java contract`() {
        val property = JsonPropertyTypeResolver.resolve(
            JavaNullabilityFixture.NonNullMapContractImplementation::class.java
        ).single()

        property.serializedName.assert().isEqualTo("inheritedJavaMap")
        property.type.run {
            nullability.assert().isEqualTo(Nullability.NON_NULL)
            arguments[0].nullability.assert().isEqualTo(Nullability.NON_NULL)
            arguments[1].nullability.assert().isEqualTo(Nullability.NON_NULL)
        }
    }
}

private data class Child(val name: String)

private data class Box<T>(val value: T)

private data class KotlinFixture(
    val nullableString: String?,
    val nullableList: List<String?>?,
    val nullableMap: Map<String, Int?>?,
    val nullableChild: Child?,
    val nullableChildren: List<Child?>,
    val boxed: Box<List<String?>?>,
)

private open class GenericBase<T>(
    @get:JsonProperty("renamed")
    val inherited: T,
)

private class StringDerived : GenericBase<String?>(null)

private data class JavaPlatformContainerFixture(
    val platformList: JavaNullabilityFixture.StringList,
    val platformMap: JavaNullabilityFixture.StringMap,
)

interface KotlinGenericListContract {
    val nonNullValues: List<String>
    val nullableElementValues: List<String?>
}

interface KotlinGenericValueContract<T> {
    val genericValues: List<T>
}

interface NullableValueContract {
    val refinedValue: String?
}

interface RefinedValueContract : NullableValueContract {
    override val refinedValue: String
}

interface ParallelNullableValueContract {
    val parallelValue: String?
}

interface ParallelNonNullValueContract {
    val parallelValue: String
}
