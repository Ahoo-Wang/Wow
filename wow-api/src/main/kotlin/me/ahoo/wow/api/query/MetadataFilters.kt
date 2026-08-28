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

import com.fasterxml.jackson.annotation.JsonTypeName

@JsonTypeName(QueryProtocol.FilterExpression.Operator.ID)
data class IdFilter(val value: String) : FilterExpression {
    override val operator = FilterOperator.ID
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.IDS)
data class IdsFilter(val values: List<String>) : FilterExpression {
    override val operator = FilterOperator.IDS

    init {
        require(values.isNotEmpty()) { "IDS values cannot be empty." }
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.AGGREGATE_ID)
data class AggregateIdFilter(val value: String) : FilterExpression {
    override val operator = FilterOperator.AGGREGATE_ID
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.AGGREGATE_IDS)
data class AggregateIdsFilter(val values: List<String>) : FilterExpression {
    override val operator = FilterOperator.AGGREGATE_IDS

    init {
        require(values.isNotEmpty()) { "AGGREGATE_IDS values cannot be empty." }
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.TENANT_ID)
data class TenantIdFilter(val value: String) : FilterExpression {
    override val operator = FilterOperator.TENANT_ID
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.OWNER_ID)
data class OwnerIdFilter(val value: String) : FilterExpression {
    override val operator = FilterOperator.OWNER_ID
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.SPACE_ID)
data class SpaceIdFilter(val value: String) : FilterExpression {
    override val operator = FilterOperator.SPACE_ID
}
