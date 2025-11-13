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

import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.AggregateName
import me.ahoo.wow.api.annotation.AggregateVersion
import me.ahoo.wow.api.annotation.OwnerId
import me.ahoo.wow.api.annotation.StaticAggregateId
import me.ahoo.wow.api.annotation.StaticTenantId
import me.ahoo.wow.api.annotation.TenantId
import me.ahoo.wow.infra.accessor.property.PropertyDescriptor.toPropertyGetter
import me.ahoo.wow.infra.accessor.property.PropertyGetter
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

/**
 * Utility object for parsing Wow framework annotations and creating property accessors.
 *
 * This object provides methods to scan properties and classes for specific Wow annotations
 * and convert them into property getters that can be used for accessing aggregate metadata
 * like IDs, names, versions, and tenant information.
 *
 * The parser supports both property-level annotations (e.g., @AggregateId on a field) and
 * class-level static annotations (e.g., @StaticAggregateId on a class).
 *
 * @see AggregateId
 * @see AggregateName
 * @see AggregateVersion
 * @see TenantId
 * @see OwnerId
 * @see StaticAggregateId
 * @see StaticTenantId
 */
object AnnotationPropertyAccessorParser {
    /**
     * Converts a Kotlin property to a string property getter.
     *
     * This method validates that the property returns a String type and creates
     * a PropertyGetter for accessing the property value.
     *
     * Example usage:
     * ```kotlin
     * class User(val name: String)
     *
     * val user = User("John")
     * val nameGetter = User::name.toStringGetter()
     * val name = nameGetter.get(user) // Returns "John"
     * ```
     *
     * @param T the type of the object containing the property
     * @return a PropertyGetter that can retrieve the string value of this property
     * @throws IllegalArgumentException if the property is not of type String
     */
    fun <T> KProperty1<T, *>.toStringGetter(): PropertyGetter<T, String> {
        require(this.returnType.classifier == String::class) {
            "Property[$this] must be of type String."
        }
        @Suppress("UNCHECKED_CAST")
        return (this as KProperty1<T, String>).toPropertyGetter()
    }

    /**
     * Converts a Kotlin property to an integer property getter.
     *
     * This method validates that the property returns an Int type and creates
     * a PropertyGetter for accessing the property value.
     *
     * Example usage:
     * ```kotlin
     * class Order(val version: Int)
     *
     * val order = Order(1)
     * val versionGetter = Order::version.toIntGetter()
     * val version = versionGetter.get(order) // Returns 1
     * ```
     * @param T the type of the object containing the property
     * @return a PropertyGetter that can retrieve the integer value of this property
     * @throws IllegalArgumentException if the property is not of type Int
     */
    fun <T> KProperty1<T, *>.toIntGetter(): PropertyGetter<T, Int> {
        require(this.returnType.classifier == Int::class) {
            "Property[$this] must be of type Int."
        }
        @Suppress("UNCHECKED_CAST")
        return (this as KProperty1<T, Int>).toPropertyGetter()
    }

    /**
     * Creates a property getter for the aggregate name if the property is annotated with @AggregateName.
     *
     * This method scans the property for the @AggregateName annotation and, if present,
     * converts the property to a string getter for accessing the aggregate name.
     *
     * Example usage:
     * ```kotlin
     * class OrderCommand(
     *     @AggregateName
     *     val aggregateName: String = "order"
     * )
     *
     * val command = OrderCommand()
     * val nameGetter = OrderCommand::aggregateName.toAggregateNameGetterIfAnnotated()
     * val name = nameGetter?.get(command) // Returns "order"
     * ```
     *
     * @param T the type of the object containing the property
     * @return a PropertyGetter for the aggregate name, or null if the property is not annotated
     * @throws IllegalArgumentException if the annotated property is not of type String
     * @see AggregateName
     */
    fun <T> KProperty1<T, *>.toAggregateNameGetterIfAnnotated(): PropertyGetter<T, String>? {
        return this.scanAnnotation<AggregateName>()?.let {
            return toStringGetter()
        }
    }

    /**
     * Creates a property getter for the aggregate ID if the property is annotated with @AggregateId.
     *
     * This method scans the property for the @AggregateId annotation and, if present,
     * converts the property to a string getter for accessing the aggregate identifier.
     *
     * Example usage:
     * ```kotlin
     * class OrderCommand(
     *     @AggregateId
     *     val orderId: String
     * )
     *
     * val command = OrderCommand("order-123")
     * val idGetter = OrderCommand::orderId.toAggregateIdGetterIfAnnotated()
     * val id = idGetter?.get(command) // Returns "order-123"
     * ```
     *
     * @param T the type of the object containing the property
     * @return a PropertyGetter for the aggregate ID, or null if the property is not annotated
     * @throws IllegalArgumentException if the annotated property is not of type String
     * @see AggregateId
     */
    fun <T> KProperty1<T, *>.toAggregateIdGetterIfAnnotated(): PropertyGetter<T, String>? {
        return this.scanAnnotation<AggregateId>()?.let {
            return toStringGetter()
        }
    }

    /**
     * Creates a property getter for the static aggregate ID if the class is annotated with @StaticAggregateId.
     *
     * This method scans the class for the @StaticAggregateId annotation and, if present,
     * creates a property getter for the specified aggregate ID property.
     *
     * Example usage:
     * ```kotlin
     * @StaticAggregateId(aggregateId = "fixed-order-id")
     * class OrderAggregate
     *
     * val idGetter = OrderAggregate::class.toStaticAggregateIdGetterIfAnnotated()
     * val id = idGetter?.get(OrderAggregate()) // Returns "fixed-order-id"
     * ```
     * @param T the type of the class
     * @return a PropertyGetter for the static aggregate ID, or null if the class is not annotated
     *
     * @see StaticAggregateId
     */
    fun <T : Any> KClass<T>.toStaticAggregateIdGetterIfAnnotated(): PropertyGetter<T, String>? =
        this.scanAnnotation<StaticAggregateId>()?.aggregateId?.toPropertyGetter()

    /**
     * Creates a property getter for the static aggregate ID using reified generics.
     *
     * This inline function provides a convenient way to get the static aggregate ID getter
     * without explicitly specifying the class type.
     *
     * Example usage:
     * ```kotlin
     * @StaticAggregateId(aggregateId = "fixed-order-id")
     * class OrderAggregate
     *
     * val idGetter = staticAggregateIdGetterIfAnnotated<OrderAggregate>()
     * val id = idGetter?.get(OrderAggregate()) // Returns "fixed-order-id"
     * ```
     * @param T the type of the class (inferred at compile time)
     * @return a PropertyGetter for the static aggregate ID, or null if the class is not annotated
     *
     * @see StaticAggregateId
     */
    inline fun <reified T : Any> staticAggregateIdGetterIfAnnotated(): PropertyGetter<T, String>? =
        T::class.toStaticAggregateIdGetterIfAnnotated()

    /**
     * Creates a property getter for the tenant ID if the property is annotated with @TenantId.
     *
     * This method scans the property for the @TenantId annotation and, if present,
     * converts the property to a string getter for accessing the tenant identifier.
     *
     * Example usage:
     * ```kotlin
     * class MultiTenantCommand(
     *     @TenantId
     *     val tenantId: String
     * )
     *
     * val command = MultiTenantCommand("tenant-456")
     * val tenantGetter = MultiTenantCommand::tenantId.toTenantIdGetterIfAnnotated()
     * val tenantId = tenantGetter?.get(command) // Returns "tenant-456"
     * ```
     * @param T the type of the object containing the property
     * @return a PropertyGetter for the tenant ID, or null if the property is not annotated
     * @throws IllegalArgumentException if the annotated property is not of type String
     *
     * @see TenantId
     */
    fun <T> KProperty1<T, *>.toTenantIdGetterIfAnnotated(): PropertyGetter<T, String>? {
        return this.scanAnnotation<TenantId>()?.let {
            return toStringGetter()
        }
    }

    /**
     * Creates a property getter for the owner ID if the property is annotated with @OwnerId.
     *
     * This method scans the property for the @OwnerId annotation and, if present,
     * converts the property to a string getter for accessing the owner identifier.
     *
     * Example usage:
     * ```kotlin
     * class UserOwnedCommand(
     *     @OwnerId
     *     val userId: String
     * )
     *
     * val command = UserOwnedCommand("user-789")
     * val ownerGetter = UserOwnedCommand::userId.toOwnerIdGetterIfAnnotated()
     * val ownerId = ownerGetter?.get(command) // Returns "user-789"
     * ```
     *
     * @param T the type of the object containing the property
     * @return a PropertyGetter for the owner ID, or null if the property is not annotated
     * @throws IllegalArgumentException if the annotated property is not of type String
     * @see OwnerId
     */
    fun <T> KProperty1<T, *>.toOwnerIdGetterIfAnnotated(): PropertyGetter<T, String>? {
        return this.scanAnnotation<OwnerId>()?.let {
            return toStringGetter()
        }
    }

    /**
     * Creates a property getter for the static tenant ID if the class is annotated with @StaticTenantId.
     *
     * This method scans the class for the @StaticTenantId annotation and, if present,
     * creates a property getter for the specified tenant ID property.
     *
     * Example usage:
     * ```kotlin
     * @StaticTenantId(tenantId = "default-tenant")
     * class DefaultTenantAggregate
     *
     * val tenantGetter = DefaultTenantAggregate::class.toStaticTenantIdGetterIfAnnotated()
     * val tenantId = tenantGetter?.get(DefaultTenantAggregate()) // Returns "default-tenant"
     * ```
     * @param T the type of the class
     * @return a PropertyGetter for the static tenant ID, or null if the class is not annotated
     *
     * @see StaticTenantId
     */
    fun <T : Any> KClass<T>.toStaticTenantIdGetterIfAnnotated(): PropertyGetter<T, String>? =
        this.scanAnnotation<StaticTenantId>()?.tenantId?.toPropertyGetter()

    /**
     * Creates a property getter for the aggregate version if the property is annotated with @AggregateVersion.
     *
     * This method scans the property for the @AggregateVersion annotation and, if present,
     * converts the property to an integer getter for accessing the aggregate version.
     *
     * Example usage:
     * ```kotlin
     * class VersionedCommand(
     *     @AggregateVersion
     *     val version: Int
     * )
     *
     * val command = VersionedCommand(42)
     * val versionGetter = VersionedCommand::version.toAggregateVersionGetterIfAnnotated()
     * val version = versionGetter?.get(command) // Returns 42
     * ```
     * @param T the type of the object containing the property
     * @return a PropertyGetter for the aggregate version, or null if the property is not annotated
     * @throws IllegalArgumentException if the annotated property is not of type Int
     *
     * @see AggregateVersion
     */
    fun <T> KProperty1<T, *>.toAggregateVersionGetterIfAnnotated(): PropertyGetter<T, Int>? {
        return this.scanAnnotation<AggregateVersion>()?.let {
            return toIntGetter()
        }
    }
}
