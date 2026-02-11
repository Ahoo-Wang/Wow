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

package me.ahoo.wow.bi.expansion

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.bi.expansion.SqlTypeMapping.isSimple
import me.ahoo.wow.bi.expansion.TableNaming.toDistributedTableName
import me.ahoo.wow.bi.expansion.column.ArrayJoinColumn
import me.ahoo.wow.bi.expansion.column.ArrayObjectColumn
import me.ahoo.wow.bi.expansion.column.Column
import me.ahoo.wow.bi.expansion.column.Column.Companion.mapColumn
import me.ahoo.wow.bi.expansion.column.SimpleArrayColumn
import me.ahoo.wow.bi.expansion.column.StatePropertyColumn
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.serialization.JsonSerializer
import tools.jackson.databind.introspect.BeanPropertyDefinition

class StateExpansionScriptGenerator(
    private val column: Column,
    private val sqlBuilder: SqlBuilder
) {
    companion object {
        private const val KOTLIN_DURATION_SUFFIX = "-LRDsOJo"
        fun NamedAggregate.toScriptGenerator(): StateExpansionScriptGenerator {
            val type = this.requiredAggregateType<Any>().aggregateMetadata<Any, Any>().state.aggregateType
            val javaType = JsonSerializer.constructType(type)
            val sourceTableName = this.toDistributedTableName("state_last")
            val targetTableName = sourceTableName + "_root"
            val sqlBuilder = SqlBuilder(targetTableName = targetTableName, sourceTableName = sourceTableName)
            return StateExpansionScriptGenerator(StatePropertyColumn("state", null, javaType), sqlBuilder)
        }
    }

    private val sqlBuilders: List<SqlBuilder> by lazy {
        build()
    }

    private val generators: MutableList<StateExpansionScriptGenerator> = mutableListOf()

    val targetTables: List<String>
        get() {
            return sqlBuilders.map { it.targetTableName }.distinct()
        }

    private fun start() {
        if (this.column is ArrayJoinColumn) {
            sqlBuilder.append(this.column)
        }
    }

    private val Column.isTooDeep: Boolean
        get() {
            return parent?.parent?.parent?.parent?.parent?.parent != null
        }

    private fun visitProperty(beanPropertyDefinition: BeanPropertyDefinition) {
        val propertyName = beanPropertyDefinition.name
        val returnType = beanPropertyDefinition.primaryType
        val column = StatePropertyColumn(propertyName, type = returnType, parent = this.column)
        if (column.isSimple) {
            sqlBuilder.append(column)
            return
        }
        if (column.isNested) {
            sqlBuilder.append(column)
            if (column.isTooDeep) {
                return
            }
            val generator = StateExpansionScriptGenerator(column, sqlBuilder)
            generators.add(generator)
            return
        }

        if (column.isMap) {
            val mapColumn = returnType.mapColumn(propertyName, parent = this.column)
            sqlBuilder.append(mapColumn)
            return
        }
        if (column.isCollection) {
            val elementType = returnType.contentType
            if (elementType.rawClass.isSimple) {
                val simpleArrayColumn = SimpleArrayColumn(propertyName, type = elementType, parent = this.column)
                sqlBuilder.append(simpleArrayColumn)
                return
            }
            val parentArrayObjectColumn = ArrayObjectColumn(propertyName, type = returnType, parent = this.column)
            sqlBuilder.append(parentArrayObjectColumn)
            val collectionTargetTableName = sqlBuilder.targetTableName + "_" + column.targetName
            val collectionSqlBuilder = sqlBuilder.copy(collectionTargetTableName)
            val collectionColumn = ArrayJoinColumn(column.name, type = elementType, parent = this.column)
            if (collectionColumn.isTooDeep) {
                return
            }
            val generator = StateExpansionScriptGenerator(collectionColumn, collectionSqlBuilder)
            generators.add(generator)
            return
        }
    }

    override fun toString(): String {
        return sqlBuilders.distinct().joinToString("\n") {
            it.build()
        }
    }

    private fun build(): List<SqlBuilder> {
        val builders = mutableListOf<SqlBuilder>()
        start()
        JsonSerializer.typeFactory.
        val beanDescription = JsonSerializer.serializationConfig.introspect(column.type)
        beanDescription.findProperties().filter {
            !it.name.endsWith(KOTLIN_DURATION_SUFFIX)
        }.forEach {
            visitProperty(it)
        }
        builders.add(sqlBuilder)
        generators.forEach {
            builders.addAll(it.build())
        }
        return builders
    }
}
