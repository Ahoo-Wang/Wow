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

package me.ahoo.wow.api.modeling

import me.ahoo.wow.api.Identifier

/**
 * 比较当前AggregateId对象是否与另一个对象相等
 * 此方法重写了equals方法，用于比较两个AggregateId对象是否在业务逻辑上相等
 * 它不仅比较对象引用，还比较对象的各个属性值
 *
 * @param other 可能与当前对象相等的另一个对象
 * @return 如果两个对象相等则返回true，否则返回false
 */
fun AggregateId.equalTo(other: Any?): Boolean {
    return when {
        // 比较对象引用，如果引用相等则对象肯定相等
        this === other -> true
        // 检查other是否为AggregateId类型，如果不是则不相等
        other !is AggregateId -> false
        // 依次比较tenantId、contextName、aggregateName和id属性
        // 如果有任何一个属性不相等，则两个对象不相等
        tenantId != other.tenantId -> false
        contextName != other.contextName -> false
        aggregateName != other.aggregateName -> false
        id != other.id -> false
        // 如果所有属性都相等，则两个对象相等
        else -> true
    }
}

/**
 * 定义哈希计算中的魔数常量
 */
private const val HASH_MAGIC = 31

/**
 * 计算AggregateId的哈希值
 *
 * 此方法通过结合租户ID、上下文名称、聚合名称和ID的哈希值来生成一个唯一的哈希值
 * 使用魔数常量HASH_MAGIC来确保哈希值的均匀分布和一致性
 *
 * @return AggregateId的哈希值
 */
fun AggregateId.hash(): Int {
    var result = tenantId.hashCode()
    result = HASH_MAGIC * result + contextName.hashCode()
    result = HASH_MAGIC * result + aggregateName.hashCode()
    result = HASH_MAGIC * result + id.hashCode()
    return result
}

/**
 * 计算AggregateId的ID哈希值除以给定除数的余数
 *
 * 此方法用于在分布式系统中对聚合进行分区或分片
 * 通过取哈希值的余数，可以将聚合均匀地分布到不同的分区或分片中
 *
 * @param divisor 除数，用于计算余数
 * @return 哈希值除以给定除数的余数
 */
fun AggregateId.mod(divisor: Int): Int {
    return id.hashCode().mod(divisor)
}

/**
 * 定义一个聚合根标识符，它继承了多个接口以支持不同的功能需求.
 * 它作为一个标识符，不仅标识聚合根，还需要支持命名、装饰器模式、租户识别以及值的比较.
 *
 * @see Identifier
 * @see NamedAggregate
 * @see NamedAggregateDecorator
 * @see TenantId
 * @see Comparable
 */
interface AggregateId : Identifier, NamedAggregate, NamedAggregateDecorator, TenantId, Comparable<AggregateId> {
    /**
     * @see MaterializedNamedAggregate
     */
    override val namedAggregate: NamedAggregate

    /**
     * 比较两个 AggregateId 的大小，首先确保它们属于同一个聚合根.
     * 如果不属于同一个聚合根，则抛出 IllegalArgumentException 异常.
     * 否则，使用标识符进行比较.
     *
     * @param other 要比较的另一个 AggregateId 实例.
     * @return 返回值遵循 Comparable 接口的约定：负数表示 this 小于 other，0 表示相等，正数表示 this 大于 other.
     * @throws IllegalArgumentException 如果两个 AggregateId 不属于同一个聚合根.
     */
    override fun compareTo(other: AggregateId): Int {
        require(isSameAggregateName(other)) {
            "NamedAggregate[$namedAggregate VS ${other.namedAggregate}] are different and cannot be compared."
        }
        return id.compareTo(other.id)
    }
}
