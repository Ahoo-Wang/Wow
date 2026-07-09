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

package me.ahoo.wow.bi.expansion.plan

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.bi.BiScriptDiagnostic
import me.ahoo.wow.bi.BiScriptDiagnosticCode
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.ObjectMapStrategy
import me.ahoo.wow.bi.UnsupportedTypeStrategy
import me.ahoo.wow.bi.expansion.BiTableNaming
import me.ahoo.wow.bi.expansion.SqlTypeMapping.isSimple
import me.ahoo.wow.bi.expansion.SqlTypeMapping.toSqlType
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.naming.NamingConverter
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toBeanDescription
import tools.jackson.databind.JavaType
import tools.jackson.databind.introspect.BeanPropertyDefinition
import java.util.Collections

class StateExpansionPlanner(private val options: BiScriptOptions = BiScriptOptions()) {
    private val naming = BiTableNaming(options)

    fun plan(namedAggregate: NamedAggregate): StateExpansionPlan {
        val context = PlanningContext(
            aggregate = "${namedAggregate.contextName}.${namedAggregate.aggregateName}",
            options = options.validate(),
        )
        val stateType = namedAggregate.requiredAggregateType<Any>()
            .aggregateMetadata<Any, Any>()
            .state
            .aggregateType
        val sourceTableName = naming.toDistributedTableName(namedAggregate, STATE_LAST_SUFFIX)
        val rootNode = PlanningNode(
            path = "",
            targetName = STATE_COLUMN,
            type = JsonSerializer.constructType(stateType),
            depth = 0,
        )

        buildView(
            node = rootNode,
            sourceTableName = sourceTableName,
            targetTableName = "${sourceTableName}_root",
            inheritedColumns = emptyList(),
            context = context,
        )
        return StateExpansionPlan(
            views = unmodifiableCopy(context.views),
            diagnostics = unmodifiableCopy(context.diagnostics),
        )
    }

    private fun buildView(
        node: PlanningNode,
        sourceTableName: String,
        targetTableName: String,
        inheritedColumns: List<ColumnPlan>,
        context: PlanningContext,
        arrayJoin: CollectionRequest? = null,
    ) {
        val draft = ViewDraft(
            anchorTargetName = node.targetName,
            columns = inheritedColumns.toMutableList(),
        )
        arrayJoin?.let { request ->
            draft.columns.add(
                ColumnPlan(
                    name = request.name,
                    path = request.path,
                    targetName = request.targetName,
                    sqlType = "String",
                    extraction = ColumnExtraction.ArrayJoin(request.sourceName, request.name),
                    placement = ColumnPlacement.WITH,
                )
            )
        }

        collectObjectProperties(node, draft, context)

        val frozenColumns = unmodifiableCopy(draft.columns)
        context.views.add(
            ExpansionViewPlan(
                targetTableName = targetTableName,
                sourceTableName = sourceTableName,
                columns = frozenColumns,
            )
        )

        val finalInheritedColumns = frozenColumns.filter(ColumnPlan::inherited)
        draft.collectionRequests.forEach { request ->
            buildView(
                node = request.elementNode,
                sourceTableName = sourceTableName,
                targetTableName = "${targetTableName}_${request.tableSuffix}",
                inheritedColumns = finalInheritedColumns,
                context = context,
                arrayJoin = request,
            )
        }
    }

    private fun collectObjectProperties(
        parent: PlanningNode,
        draft: ViewDraft,
        context: PlanningContext,
    ) {
        parent.type.toBeanDescription()
            .findProperties()
            .asSequence()
            .filter(PropertyFilter::shouldInclude)
            .sortedBy(BeanPropertyDefinition::getName)
            .forEach { property ->
                collectProperty(parent, property, draft, context)
            }
    }

    private fun collectProperty(
        parent: PlanningNode,
        property: BeanPropertyDefinition,
        draft: ViewDraft,
        context: PlanningContext,
    ) {
        val name = property.name
        val type = property.primaryType
        val path = childPath(parent.path, name)
        val targetName = childTargetName(parent, name)

        if (shouldTruncateBeforeClassification(parent, type, context)) {
            collectTruncatedProperty(
                name = name,
                path = path,
                targetName = targetName,
                sourceName = parent.targetName,
                type = type,
                draft = draft,
                context = context,
            )
            return
        }

        when {
            type.rawClass.isSimple -> draft.columns.add(
                simpleColumn(name, path, targetName, parent.targetName, type)
            )

            type.isMapLikeType -> draft.columns.add(
                mapColumn(name, path, targetName, parent.targetName, type, context)
            )

            type.isCollectionLikeType || type.isArrayType -> collectCollection(
                parent = parent,
                name = name,
                path = path,
                targetName = targetName,
                type = type,
                draft = draft,
                context = context,
            )

            isUnsupportedPlatformObject(type) -> collectUnsupported(
                name = name,
                path = path,
                targetName = targetName,
                sourceName = parent.targetName,
                type = type,
                draft = draft,
                context = context,
            )

            else -> collectNestedObject(
                parent = parent,
                name = name,
                path = path,
                targetName = targetName,
                type = type,
                draft = draft,
                context = context,
            )
        }
    }

    private fun shouldTruncateBeforeClassification(
        parent: PlanningNode,
        type: JavaType,
        context: PlanningContext,
    ): Boolean {
        if (parent.depth + 1 <= context.options.maxExpansionDepth) {
            return false
        }
        if (type.rawClass.isSimple) {
            return false
        }
        if (type.isCollectionLikeType || type.isArrayType) {
            return false
        }
        if (type.isMapLikeType) {
            return type.keyType?.rawClass != String::class.java ||
                type.contentType?.rawClass?.isSimple != true
        }
        return isUnsupportedPlatformObject(type)
    }

    private fun collectTruncatedProperty(
        name: String,
        path: String,
        targetName: String,
        sourceName: String,
        type: JavaType,
        draft: ViewDraft,
        context: PlanningContext,
    ) {
        draft.columns.add(
            ColumnPlan(
                name = name,
                path = path,
                targetName = targetName,
                sqlType = "String",
                extraction = ColumnExtraction.JsonString(sourceName, name),
                placement = ColumnPlacement.SELECT,
            )
        )
        context.diagnostics.add(depthDiagnostic(context.aggregate, path, type))
    }

    private fun collectNestedObject(
        parent: PlanningNode,
        name: String,
        path: String,
        targetName: String,
        type: JavaType,
        draft: ViewDraft,
        context: PlanningContext,
    ) {
        val propertyDepth = parent.depth + 1
        val truncated = propertyDepth > context.options.maxExpansionDepth
        if (truncated) {
            collectTruncatedProperty(
                name = name,
                path = path,
                targetName = targetName,
                sourceName = parent.targetName,
                type = type,
                draft = draft,
                context = context,
            )
            return
        }
        draft.columns.add(
            ColumnPlan(
                name = name,
                path = path,
                targetName = targetName,
                sqlType = "String",
                extraction = ColumnExtraction.JsonString(parent.targetName, name),
                placement = ColumnPlacement.WITH,
            )
        )

        collectObjectProperties(
            parent = PlanningNode(
                path = path,
                targetName = targetName,
                type = type,
                depth = propertyDepth,
            ),
            draft = draft,
            context = context,
        )
    }

    private fun collectCollection(
        parent: PlanningNode,
        name: String,
        path: String,
        targetName: String,
        type: JavaType,
        draft: ViewDraft,
        context: PlanningContext,
    ) {
        val elementType = type.contentType
        if (elementType?.rawClass?.isSimple == true) {
            val elementSqlType = elementType.rawClass.toSqlType()
            draft.columns.add(
                ColumnPlan(
                    name = name,
                    path = path,
                    targetName = targetName,
                    sqlType = "Array($elementSqlType)",
                    extraction = ColumnExtraction.JsonValue(parent.targetName, name),
                    placement = ColumnPlacement.SELECT,
                )
            )
            return
        }

        val propertyDepth = parent.depth + 1
        val truncated = propertyDepth > context.options.maxExpansionDepth
        draft.columns.add(
            rawObjectArrayColumn(name, path, targetName, parent.targetName)
        )
        if (truncated) {
            context.diagnostics.add(depthDiagnostic(context.aggregate, path, type))
            return
        }
        if (elementType == null || isUnsupportedPlatformObject(elementType)) {
            addUnsupportedTypeDiagnostic(
                context = context,
                path = path,
                typeName = type.toCanonical(),
                subject = "Unsupported collection element type",
                fallback = "Array(String)",
            )
            return
        }

        draft.collectionRequests.add(
            CollectionRequest(
                name = name,
                path = path,
                sourceName = parent.targetName,
                targetName = targetName,
                tableSuffix = relativeTargetName(draft.anchorTargetName, targetName),
                elementNode = PlanningNode(
                    path = path,
                    targetName = targetName,
                    type = elementType,
                    depth = propertyDepth,
                ),
            )
        )
    }

    private fun rawObjectArrayColumn(
        name: String,
        path: String,
        targetName: String,
        sourceName: String,
    ): ColumnPlan {
        return ColumnPlan(
            name = name,
            path = path,
            targetName = targetName,
            sqlType = "Array(String)",
            extraction = ColumnExtraction.JsonArray(sourceName, name),
            placement = ColumnPlacement.SELECT,
            inherited = false,
        )
    }

    private fun collectUnsupported(
        name: String,
        path: String,
        targetName: String,
        sourceName: String,
        type: JavaType,
        draft: ViewDraft,
        context: PlanningContext,
    ) {
        val typeName = type.rawClass.name
        require(context.options.unsupportedTypeStrategy != UnsupportedTypeStrategy.FAIL) {
            "Unsupported type property [$path] for aggregate [${context.aggregate}] with type [$typeName]."
        }
        context.diagnostics.add(
            BiScriptDiagnostic(
                code = BiScriptDiagnosticCode.UNSUPPORTED_TYPE_FALLBACK,
                severity = BiScriptDiagnostic.Severity.WARNING,
                aggregate = context.aggregate,
                path = path,
                message = "Unsupported type property [$path] with type [$typeName] is rendered as String.",
            )
        )
        draft.columns.add(
            ColumnPlan(
                name = name,
                path = path,
                targetName = targetName,
                sqlType = "String",
                extraction = ColumnExtraction.JsonString(sourceName, name),
                placement = ColumnPlacement.SELECT,
            )
        )
    }

    private fun mapColumn(
        name: String,
        path: String,
        targetName: String,
        sourceName: String,
        type: JavaType,
        context: PlanningContext,
    ): ColumnPlan {
        val mapTypeName = type.toCanonical()
        if (type.keyType?.rawClass != String::class.java) {
            addUnsupportedTypeDiagnostic(
                context = context,
                path = path,
                typeName = mapTypeName,
                subject = "Unsupported map key type",
                fallback = "raw String",
            )
            return ColumnPlan(
                name = name,
                path = path,
                targetName = targetName,
                sqlType = "String",
                extraction = ColumnExtraction.JsonString(sourceName, name),
                placement = ColumnPlacement.SELECT,
            )
        }
        val valueType = type.contentType
        val sqlType = if (valueType.rawClass.isSimple) {
            "Map(String, ${valueType.rawClass.toSqlType()})"
        } else {
            val valueTypeName = valueType.toCanonical()
            require(context.options.objectMapStrategy != ObjectMapStrategy.FAIL) {
                "Unsupported object map property [$path] for aggregate [${context.aggregate}] " +
                    "with value type [$valueTypeName]."
            }
            context.diagnostics.add(
                BiScriptDiagnostic(
                    code = BiScriptDiagnosticCode.OBJECT_MAP_FALLBACK,
                    severity = BiScriptDiagnostic.Severity.WARNING,
                    aggregate = context.aggregate,
                    path = path,
                    message = "Object map property [$path] with value type [$valueTypeName] " +
                        "is rendered as Map(String, String).",
                )
            )
            "Map(String, String)"
        }
        return ColumnPlan(
            name = name,
            path = path,
            targetName = targetName,
            sqlType = sqlType,
            extraction = ColumnExtraction.JsonValue(sourceName, name),
            placement = ColumnPlacement.SELECT,
        )
    }

    private fun addUnsupportedTypeDiagnostic(
        context: PlanningContext,
        path: String,
        typeName: String,
        subject: String,
        fallback: String,
    ) {
        require(context.options.unsupportedTypeStrategy != UnsupportedTypeStrategy.FAIL) {
            "$subject property [$path] for aggregate [${context.aggregate}] with type [$typeName]."
        }
        context.diagnostics.add(
            BiScriptDiagnostic(
                code = BiScriptDiagnosticCode.UNSUPPORTED_TYPE_FALLBACK,
                severity = BiScriptDiagnostic.Severity.WARNING,
                aggregate = context.aggregate,
                path = path,
                message = "$subject property [$path] with type [$typeName] is rendered as $fallback.",
            )
        )
    }

    private fun simpleColumn(
        name: String,
        path: String,
        targetName: String,
        sourceName: String,
        type: JavaType,
    ): ColumnPlan {
        return ColumnPlan(
            name = name,
            path = path,
            targetName = targetName,
            sqlType = type.rawClass.toSqlType(),
            extraction = ColumnExtraction.JsonValue(sourceName, name),
            placement = ColumnPlacement.SELECT,
        )
    }

    private fun depthDiagnostic(aggregate: String, path: String, type: JavaType): BiScriptDiagnostic {
        return BiScriptDiagnostic(
            code = BiScriptDiagnosticCode.MAX_DEPTH_REACHED,
            severity = BiScriptDiagnostic.Severity.WARNING,
            aggregate = aggregate,
            path = path,
            message = "Max expansion depth reached at [$path] with type [${type.rawClass.name}].",
        )
    }

    private fun childPath(parentPath: String, name: String): String {
        return if (parentPath.isBlank()) name else "$parentPath.$name"
    }

    private fun childTargetName(parent: PlanningNode, name: String): String {
        val propertyName = NamingConverter.PASCAL_TO_SNAKE.convert(name)
        return if (parent.depth == 0) propertyName else "${parent.targetName}__$propertyName"
    }

    private fun relativeTargetName(anchorTargetName: String, targetName: String): String {
        if (anchorTargetName == STATE_COLUMN) {
            return targetName
        }
        return targetName.removePrefix("${anchorTargetName}__")
    }

    private fun isUnsupportedPlatformObject(type: JavaType): Boolean {
        val packageName = type.rawClass.packageName
        return packageName.startsWith("java.") ||
            packageName.startsWith("javax.") ||
            packageName.startsWith("kotlin.")
    }

    private fun <T> unmodifiableCopy(values: Collection<T>): List<T> {
        return Collections.unmodifiableList(ArrayList(values))
    }

    private companion object {
        const val STATE_COLUMN = "state"
        const val STATE_LAST_SUFFIX = "state_last"
    }
}

private data class PlanningNode(
    val path: String,
    val targetName: String,
    val type: JavaType,
    val depth: Int,
)

private data class CollectionRequest(
    val name: String,
    val path: String,
    val sourceName: String,
    val targetName: String,
    val tableSuffix: String,
    val elementNode: PlanningNode,
)

private data class ViewDraft(
    val anchorTargetName: String,
    val columns: MutableList<ColumnPlan>,
    val collectionRequests: MutableList<CollectionRequest> = mutableListOf(),
)

private class PlanningContext(
    val aggregate: String,
    val options: BiScriptOptions,
) {
    val views: MutableList<ExpansionViewPlan> = mutableListOf()
    val diagnostics: MutableList<BiScriptDiagnostic> = mutableListOf()
}
