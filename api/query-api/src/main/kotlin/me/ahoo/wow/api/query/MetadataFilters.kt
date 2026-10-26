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

package me.ahoo.wow.api.query

import me.ahoo.wow.api.annotation.InternalWowApi

class IdFilter @InternalWowApi constructor(val value: String) : FilterExpression {
    override val operator = FilterOperator.ID

    /** This IdFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        value: String = this.value,
    ): IdFilter = IdFilter(value)

    override fun equals(other: Any?): Boolean =
        this === other || other is IdFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "IdFilter(value=$value)"

    private fun valueFields(): List<Any?> = listOf(value)

    companion object {
        /** A [IdFilter] of the given values. */
        @JvmStatic
        fun of(value: String): IdFilter = IdFilter(value)
    }
}

class IdsFilter @InternalWowApi constructor(val values: List<String>) : FilterExpression {
    override val operator = FilterOperator.IDS

    init {
        require(values.isNotEmpty()) { "IDS values cannot be empty." }
    }

    /** This IdsFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        values: List<String> = this.values,
    ): IdsFilter = IdsFilter(values)

    override fun equals(other: Any?): Boolean =
        this === other || other is IdsFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "IdsFilter(values=$values)"

    private fun valueFields(): List<Any?> = listOf(values)

    companion object {
        /** A [IdsFilter] of the given values. */
        @JvmStatic
        fun of(values: List<String>): IdsFilter = IdsFilter(values)
    }
}

class AggregateIdFilter @InternalWowApi constructor(val value: String) : FilterExpression {
    override val operator = FilterOperator.AGGREGATE_ID

    /** This AggregateIdFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        value: String = this.value,
    ): AggregateIdFilter = AggregateIdFilter(value)

    override fun equals(other: Any?): Boolean =
        this === other || other is AggregateIdFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "AggregateIdFilter(value=$value)"

    private fun valueFields(): List<Any?> = listOf(value)

    companion object {
        /** A [AggregateIdFilter] of the given values. */
        @JvmStatic
        fun of(value: String): AggregateIdFilter = AggregateIdFilter(value)
    }
}

class AggregateIdsFilter @InternalWowApi constructor(val values: List<String>) : FilterExpression {
    override val operator = FilterOperator.AGGREGATE_IDS

    init {
        require(values.isNotEmpty()) { "AGGREGATE_IDS values cannot be empty." }
    }

    /** This AggregateIdsFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        values: List<String> = this.values,
    ): AggregateIdsFilter = AggregateIdsFilter(values)

    override fun equals(other: Any?): Boolean =
        this === other || other is AggregateIdsFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "AggregateIdsFilter(values=$values)"

    private fun valueFields(): List<Any?> = listOf(values)

    companion object {
        /** A [AggregateIdsFilter] of the given values. */
        @JvmStatic
        fun of(values: List<String>): AggregateIdsFilter = AggregateIdsFilter(values)
    }
}

class TenantIdFilter @InternalWowApi constructor(val value: String) : FilterExpression {
    override val operator = FilterOperator.TENANT_ID

    /** This TenantIdFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        value: String = this.value,
    ): TenantIdFilter = TenantIdFilter(value)

    override fun equals(other: Any?): Boolean =
        this === other || other is TenantIdFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "TenantIdFilter(value=$value)"

    private fun valueFields(): List<Any?> = listOf(value)

    companion object {
        /** A [TenantIdFilter] of the given values. */
        @JvmStatic
        fun of(value: String): TenantIdFilter = TenantIdFilter(value)
    }
}

class OwnerIdFilter @InternalWowApi constructor(val value: String) : FilterExpression {
    override val operator = FilterOperator.OWNER_ID

    /** This OwnerIdFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        value: String = this.value,
    ): OwnerIdFilter = OwnerIdFilter(value)

    override fun equals(other: Any?): Boolean =
        this === other || other is OwnerIdFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "OwnerIdFilter(value=$value)"

    private fun valueFields(): List<Any?> = listOf(value)

    companion object {
        /** A [OwnerIdFilter] of the given values. */
        @JvmStatic
        fun of(value: String): OwnerIdFilter = OwnerIdFilter(value)
    }
}

class SpaceIdFilter @InternalWowApi constructor(val value: String) : FilterExpression {
    override val operator = FilterOperator.SPACE_ID

    /** This SpaceIdFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        value: String = this.value,
    ): SpaceIdFilter = SpaceIdFilter(value)

    override fun equals(other: Any?): Boolean =
        this === other || other is SpaceIdFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "SpaceIdFilter(value=$value)"

    private fun valueFields(): List<Any?> = listOf(value)

    companion object {
        /** A [SpaceIdFilter] of the given values. */
        @JvmStatic
        fun of(value: String): SpaceIdFilter = SpaceIdFilter(value)
    }
}
