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

package me.ahoo.wow.annotation

import me.ahoo.test.asserts.assert
import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.staticAggregateIdGetterIfAnnotated
import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.toAggregateIdGetterIfAnnotated
import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.toAggregateNameGetterIfAnnotated
import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.toAggregateVersionGetterIfAnnotated
import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.toIntGetter
import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.toOwnerIdGetterIfAnnotated
import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.toStaticTenantIdGetterIfAnnotated
import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.toStringGetter
import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.toTenantIdGetterIfAnnotated
import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.AggregateName
import me.ahoo.wow.api.annotation.AggregateVersion
import me.ahoo.wow.api.annotation.OwnerId
import me.ahoo.wow.api.annotation.StaticAggregateId
import me.ahoo.wow.api.annotation.StaticTenantId
import me.ahoo.wow.api.annotation.TenantId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AnnotationPropertyAccessorParserBehaviorTest {

    @Test
    fun `should create string and int getters for matching property types`() {
        val fixture = AnnotationAccessorFixture(
            plainText = "plain",
            number = 7,
            aggregateName = "cart",
            aggregateId = "cart-1",
            tenantId = "tenant-1",
            ownerId = "owner-1",
            aggregateVersion = 3,
        )

        AnnotationAccessorFixture::plainText.toStringGetter()[fixture].assert().isEqualTo("plain")
        AnnotationAccessorFixture::number.toIntGetter()[fixture].assert().isEqualTo(7)
    }

    @Test
    fun `should reject property getter creation when property type differs`() {
        assertThrows<IllegalArgumentException> {
            AnnotationAccessorFixture::number.toStringGetter()
        }.message.assert().isEqualTo(
            "Property[val me.ahoo.wow.annotation.AnnotationAccessorFixture.number: kotlin.Int] must be of type String.",
        )

        assertThrows<IllegalArgumentException> {
            AnnotationAccessorFixture::plainText.toIntGetter()
        }.message.assert().isEqualTo(
            "Property[val me.ahoo.wow.annotation.AnnotationAccessorFixture.plainText: kotlin.String] must be of type Int.",
        )
    }

    @Test
    fun `should create accessors for annotated aggregate properties`() {
        val fixture = AnnotationAccessorFixture(
            plainText = "plain",
            number = 7,
            aggregateName = "cart",
            aggregateId = "cart-1",
            tenantId = "tenant-1",
            ownerId = "owner-1",
            aggregateVersion = 3,
        )

        AnnotationAccessorFixture::aggregateName.toAggregateNameGetterIfAnnotated()!![fixture].assert()
            .isEqualTo("cart")
        AnnotationAccessorFixture::aggregateId.toAggregateIdGetterIfAnnotated()!![fixture].assert()
            .isEqualTo("cart-1")
        AnnotationAccessorFixture::tenantId.toTenantIdGetterIfAnnotated()!![fixture].assert()
            .isEqualTo("tenant-1")
        AnnotationAccessorFixture::ownerId.toOwnerIdGetterIfAnnotated()!![fixture].assert()
            .isEqualTo("owner-1")
        AnnotationAccessorFixture::aggregateVersion.toAggregateVersionGetterIfAnnotated()!![fixture].assert()
            .isEqualTo(3)
    }

    @Test
    fun `should return null when requested annotation is absent`() {
        AnnotationAccessorFixture::plainText.toAggregateNameGetterIfAnnotated().assert().isNull()
        AnnotationAccessorFixture::plainText.toAggregateIdGetterIfAnnotated().assert().isNull()
        AnnotationAccessorFixture::plainText.toTenantIdGetterIfAnnotated().assert().isNull()
        AnnotationAccessorFixture::plainText.toOwnerIdGetterIfAnnotated().assert().isNull()
        AnnotationAccessorFixture::plainText.toAggregateVersionGetterIfAnnotated().assert().isNull()
    }

    @Test
    fun `should create static aggregate and tenant accessors from class annotations`() {
        val fixture = AnnotationAccessorFixture(
            plainText = "plain",
            number = 7,
            aggregateName = "cart",
            aggregateId = "cart-1",
            tenantId = "tenant-1",
            ownerId = "owner-1",
            aggregateVersion = 3,
        )

        staticAggregateIdGetterIfAnnotated<AnnotationAccessorFixture>()!![fixture].assert()
            .isEqualTo("static-aggregate")
        AnnotationAccessorFixture::class.toStaticTenantIdGetterIfAnnotated()!![fixture].assert()
            .isEqualTo("static-tenant")
        UnannotatedAccessorFixture::class.toStaticTenantIdGetterIfAnnotated().assert().isNull()
    }
}

@StaticAggregateId("static-aggregate")
@StaticTenantId("static-tenant")
private data class AnnotationAccessorFixture(
    val plainText: String,
    val number: Int,
    @AggregateName
    @get:AggregateName
    val aggregateName: String,
    @AggregateId
    @get:AggregateId
    val aggregateId: String,
    @TenantId
    @get:TenantId
    val tenantId: String,
    @OwnerId
    @get:OwnerId
    val ownerId: String,
    @AggregateVersion
    @get:AggregateVersion
    val aggregateVersion: Int
)

private class UnannotatedAccessorFixture
