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
import me.ahoo.wow.bi.expansion.column.Column
import me.ahoo.wow.bi.expansion.column.SimpleArrayColumn
import me.ahoo.wow.bi.expansion.column.SimpleMapColumn
import me.ahoo.wow.bi.expansion.column.StatePropertyColumn
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.infra.reflection.ClassMetadata
import me.ahoo.wow.infra.reflection.ClassVisitor
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import org.jetbrains.kotlin.descriptors.runtime.structure.parameterizedTypeArguments
import java.lang.reflect.Field

class StateExpansionScriptGenerator(private val column: Column, private val sqlBuilder: SqlBuilder) :
    ClassVisitor {
    companion object {
        fun NamedAggregate.toScriptGenerator(): StateExpansionScriptGenerator {
            val type = this.requiredAggregateType<Any>().aggregateMetadata<Any, Any>().state.aggregateType
            val sourceTableName = this.toDistributedTableName("state_last")
            val targetTableName = sourceTableName + "_root"
            val sqlBuilder = SqlBuilder(targetTableName = targetTableName, sourceTableName = sourceTableName)
            return StateExpansionScriptGenerator(StatePropertyColumn("state", null, type), sqlBuilder)
        }
    }

    private var isBuilt = false
    private val sqlBuilders = mutableListOf<SqlBuilder>()
    private val generators: MutableList<StateExpansionScriptGenerator> = mutableListOf()

    override fun start() {
        if (this.column is ArrayJoinColumn) {
            sqlBuilder.append(this.column)
        }
    }

    override fun visitField(field: Field) {
        val column = StatePropertyColumn(field.name, type = field.type, parent = this.column)
        if (column.isSimple) {
            sqlBuilder.append(column)
            return
        }
        if (column.isNested) {
            sqlBuilder.append(column)
            val generator = StateExpansionScriptGenerator(column, sqlBuilder)
            generators.add(generator)
            return
        }

        if (column.isCollection) {
            val genericType = field.genericType.parameterizedTypeArguments.first() as Class<*>
            if (genericType.isSimple) {
                val simpleArrayColumn = SimpleArrayColumn(field.name, type = genericType, parent = this.column)
                sqlBuilder.append(simpleArrayColumn)
                return
            }
            val collectionTargetTableName = sqlBuilder.targetTableName + "_" + column.targetName
            val collectionSqlBuilder = sqlBuilder.copy(collectionTargetTableName)
            val collectionColumn = ArrayJoinColumn(column.name, type = genericType, parent = this.column)
            val generator = StateExpansionScriptGenerator(collectionColumn, collectionSqlBuilder)
            generators.add(generator)
            return
        }

        if (column.isMap) {
            val valueType = field.genericType.parameterizedTypeArguments[1] as Class<*>
            if (valueType.isSimple) {
                val simpleMapColumn = SimpleMapColumn(field.name, type = valueType, parent = this.column)
                sqlBuilder.append(simpleMapColumn)
                return
            }
        }
    }

    override fun toString(): String {
        return build().distinct().joinToString("\n") {
            it.build()
        }
    }

    fun build(): List<SqlBuilder> {
        if (isBuilt) {
            return sqlBuilders
        }
        ClassMetadata.visit(column.type, this)
        sqlBuilders.add(sqlBuilder)
        generators.forEach {
            sqlBuilders.addAll(it.build())
        }
        return sqlBuilders
    }
}
