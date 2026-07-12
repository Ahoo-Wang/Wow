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
import me.ahoo.wow.bi.BiScriptMappingDecision
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.UnsupportedTypeStrategy
import me.ahoo.wow.bi.expansion.BiTableNaming
import me.ahoo.wow.bi.expansion.type.JacksonWireShapeInspector
import me.ahoo.wow.bi.expansion.type.JsonWireShape
import me.ahoo.wow.bi.expansion.type.Nullability
import me.ahoo.wow.bi.expansion.type.ResolvedJsonProperty
import me.ahoo.wow.bi.expansion.type.ResolvedType
import me.ahoo.wow.bi.type.ClickHouseType
import me.ahoo.wow.bi.type.ClickHouseTypeMapping.scalarMapping
import me.ahoo.wow.bi.type.JsonTokenShape
import me.ahoo.wow.bi.type.ScalarMapping
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.naming.NamingConverter
import me.ahoo.wow.serialization.JsonSerializer
import java.util.Collections

internal class StateExpansionPlanner(private val options: BiScriptOptions = BiScriptOptions()) {
    private val naming = BiTableNaming(options)

    fun plan(namedAggregate: NamedAggregate): StateExpansionPlan {
        val context = PlanningContext(
            aggregate = "${namedAggregate.contextName}.${namedAggregate.aggregateName}",
            options = options,
        )
        val stateClass = namedAggregate.requiredAggregateType<Any>()
            .aggregateMetadata<Any, Any>()
            .state
            .aggregateType
        val stateType = JsonSerializer.constructType(stateClass)
        val sourceTableName = naming.toDistributedTableName(namedAggregate, STATE_LAST_SUFFIX)
        val rootNode = PlanningNode(
            path = "",
            pointer = emptyList(),
            source = ColumnReference.Input(STATE_COLUMN),
            targetName = STATE_COLUMN,
            type = ResolvedType(
                javaType = stateType,
                nullability = Nullability.NON_NULL,
                arguments = emptyList(),
            ),
            depth = 0,
            nullableAncestor = false,
        )

        buildView(
            node = rootNode,
            sourceTableName = sourceTableName,
            targetTableName = "${sourceTableName}_root",
            inheritedColumns = emptyList(),
            recovery = ExpansionRecoveryPlan(emptyList(), emptyList(), null),
            context = context,
        )
        return StateExpansionPlan(
            views = unmodifiableCopy(context.views),
            diagnostics = unmodifiableCopy(
                context.diagnostics.sortedWith(
                    compareBy<BiScriptDiagnostic>(BiScriptDiagnostic::path)
                        .thenBy { it.code.name }
                )
            ),
        )
    }

    private fun buildView(
        node: PlanningNode,
        sourceTableName: String,
        targetTableName: String,
        inheritedColumns: List<ColumnPlan>,
        recovery: ExpansionRecoveryPlan,
        context: PlanningContext,
        arrayJoin: CollectionRequest? = null,
    ) {
        val draft = ViewDraft(
            targetTableName = targetTableName,
            anchorTargetName = node.targetName,
            columns = inheritedColumns.toMutableList(),
        )
        arrayJoin?.let { request ->
            if (request.rawElementCompanion) {
                draft.columns.add(
                    ColumnPlan(
                        name = request.name,
                        path = request.path,
                        targetName = rawTargetName(request.targetName),
                        type = ClickHouseType.String,
                        extraction = ColumnExtraction.Reference(ColumnReference.Alias(request.targetName)),
                        placement = ColumnPlacement.SELECT,
                    )
                )
            }
        }

        collectObjectProperties(node, draft, context)
        validateColumnTargets(draft, context)

        val frozenColumns = unmodifiableCopy(draft.columns.sortedBy(ColumnPlan::targetName))
        context.views.add(
            ExpansionViewPlan(
                targetTableName = targetTableName,
                sourceTableName = sourceTableName,
                columns = frozenColumns,
                recovery = recovery,
            )
        )

        val finalInheritedColumns = frozenColumns.filter(ColumnPlan::inherited)
        draft.collectionRequests.forEach { request ->
            val childRecovery = ExpansionRecoveryPlan(
                cursors = recovery.cursors + request.cursor,
                pointer = request.pointer,
                currentIndex = request.cursor.cursor,
            )
            buildView(
                node = request.elementNode,
                sourceTableName = sourceTableName,
                targetTableName = "${targetTableName}_${request.tableSuffix}",
                inheritedColumns = finalInheritedColumns,
                recovery = childRecovery,
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
        when (val shape = JacksonWireShapeInspector.inspect(parent.type)) {
            is JsonWireShape.ExpandableObject -> collectResolvedProperties(parent, shape.properties, draft, context)
            is JsonWireShape.Opaque -> collectOpaqueNode(parent, draft, context)
        }
    }

    private fun collectResolvedProperties(
        parent: PlanningNode,
        properties: List<ResolvedJsonProperty>,
        draft: ViewDraft,
        context: PlanningContext,
    ) {
        properties.forEach { property ->
            collectProperty(parent, property, draft, context)
        }
    }

    private fun collectProperty(
        parent: PlanningNode,
        property: ResolvedJsonProperty,
        draft: ViewDraft,
        context: PlanningContext,
    ) {
        val name = property.serializedName
        val type = property.type
        val path = childPath(parent.path, name)
        val targetName = childTargetName(parent, name)
        draft.propertyTargetNames.add(targetName)

        if (parent.depth + 1 > context.options.maxExpansionDepth && !type.canRenderDirectly()) {
            collectDepthFallback(name, path, targetName, parent.source, type, draft, context)
            return
        }

        if (collectScalarProperty(parent, name, path, targetName, type, draft, context)) {
            return
        }
        when {
            type.javaType.isMapLikeType -> collectMap(
                parent = parent,
                name = name,
                path = path,
                targetName = targetName,
                type = type,
                draft = draft,
                context = context,
            )

            type.javaType.isCollectionLikeType || type.javaType.isArrayType -> collectCollection(
                parent = parent,
                name = name,
                path = path,
                targetName = targetName,
                type = type,
                draft = draft,
                context = context,
            )

            isUnsupportedPlatformObject(type) -> collectRawFallback(
                name = name,
                path = path,
                targetName = targetName,
                source = parent.source,
                type = type,
                draft = draft,
                context = context,
            )

            else -> collectObjectProperty(parent, name, path, targetName, type, draft, context)
        }
    }

    private fun collectScalarProperty(
        parent: PlanningNode,
        name: String,
        path: String,
        targetName: String,
        type: ResolvedType,
        draft: ViewDraft,
        context: PlanningContext,
    ): Boolean {
        val scalarMapping = type.rawClass.scalarMapping() ?: return false
        if (JacksonWireShapeInspector.matches(type, scalarMapping.tokenShape)) {
            collectScalar(parent, name, path, targetName, type, scalarMapping, draft)
        } else {
            collectRawFallback(name, path, targetName, parent.source, type, draft, context)
        }
        return true
    }

    private fun collectObjectProperty(
        parent: PlanningNode,
        name: String,
        path: String,
        targetName: String,
        type: ResolvedType,
        draft: ViewDraft,
        context: PlanningContext,
    ) {
        when (val shape = JacksonWireShapeInspector.inspect(type)) {
            is JsonWireShape.ExpandableObject -> collectNestedObject(
                parent = parent,
                name = name,
                path = path,
                targetName = targetName,
                type = type,
                properties = shape.properties,
                draft = draft,
                context = context,
            )

            is JsonWireShape.Opaque -> collectRawFallback(
                name = name,
                path = path,
                targetName = targetName,
                source = parent.source,
                type = type,
                draft = draft,
                context = context,
            )
        }
    }

    private fun collectScalar(
        parent: PlanningNode,
        name: String,
        path: String,
        targetName: String,
        type: ResolvedType,
        scalarMapping: ScalarMapping,
        draft: ViewDraft,
    ) {
        draft.columns.add(
            ColumnPlan(
                name = name,
                path = path,
                targetName = targetName,
                type = type.toScalarType(scalarMapping, parent.nullableAncestor),
                extraction = ColumnExtraction.JsonValue(parent.source, name),
                placement = ColumnPlacement.SELECT,
            )
        )
        if (type.requiresRawCompanion()) {
            draft.columns.add(rawCompanionColumn(name, path, targetName, parent.source))
        }
    }

    private fun collectNestedObject(
        parent: PlanningNode,
        name: String,
        path: String,
        targetName: String,
        type: ResolvedType,
        properties: List<ResolvedJsonProperty>,
        draft: ViewDraft,
        context: PlanningContext,
    ) {
        draft.columns.add(
            ColumnPlan(
                name = name,
                path = path,
                targetName = targetName,
                type = ClickHouseType.String,
                extraction = ColumnExtraction.JsonRaw(parent.source, name),
                placement = ColumnPlacement.WITH,
            )
        )
        if (type.requiresRawCompanion()) {
            draft.columns.add(rawCompanionColumn(name, path, targetName, parent.source))
        }

        collectResolvedProperties(
            parent = PlanningNode(
                path = path,
                pointer = parent.pointer + JsonPointerSegment.Property(encodePointerSegment(name)),
                source = ColumnReference.Alias(targetName),
                targetName = targetName,
                type = type,
                depth = parent.depth + 1,
                nullableAncestor = parent.nullableAncestor || type.requiresRawCompanion(),
            ),
            properties = properties,
            draft = draft,
            context = context,
        )
    }

    private fun collectMap(
        parent: PlanningNode,
        name: String,
        path: String,
        targetName: String,
        type: ResolvedType,
        draft: ViewDraft,
        context: PlanningContext,
    ) {
        val valueType = type.arguments.getOrNull(1)
        val valueMapping = type.supportedMapValueMapping()
        if (!JacksonWireShapeInspector.matches(type, JsonTokenShape.MAP) ||
            valueType == null ||
            valueMapping == null
        ) {
            collectRawFallback(name, path, targetName, parent.source, type, draft, context)
            return
        }

        draft.columns.add(
            ColumnPlan(
                name = name,
                path = path,
                targetName = targetName,
                type = ClickHouseType.Map(
                    keyType = ClickHouseType.String,
                    valueType = valueType.toScalarType(valueMapping, nullableAncestor = false),
                ),
                extraction = ColumnExtraction.JsonValue(parent.source, name),
                placement = ColumnPlacement.SELECT,
            )
        )
        if (type.requiresRawCompanion()) {
            draft.columns.add(rawCompanionColumn(name, path, targetName, parent.source))
        }
    }

    private fun collectCollection(
        parent: PlanningNode,
        name: String,
        path: String,
        targetName: String,
        type: ResolvedType,
        draft: ViewDraft,
        context: PlanningContext,
    ) {
        val elementType = type.arguments.firstOrNull()
        if (!JacksonWireShapeInspector.matches(type, JsonTokenShape.ARRAY) || elementType == null) {
            collectRawFallback(name, path, targetName, parent.source, type, draft, context)
            return
        }
        val elementMapping = elementType.verifiedScalarMapping()
        if (elementMapping != null) {
            collectScalarCollection(parent, name, path, targetName, type, elementType, elementMapping, draft)
            return
        }
        if (elementType.isOpaqueCollectionElement()) {
            collectRawFallback(name, path, targetName, parent.source, type, draft, context)
            return
        }
        collectObjectCollection(parent, name, path, targetName, type, elementType, draft)
    }

    private fun collectScalarCollection(
        parent: PlanningNode,
        name: String,
        path: String,
        targetName: String,
        type: ResolvedType,
        elementType: ResolvedType,
        elementMapping: ScalarMapping,
        draft: ViewDraft,
    ) {
        draft.columns.add(
            ColumnPlan(
                name = name,
                path = path,
                targetName = targetName,
                type = ClickHouseType.Array(elementType.toScalarType(elementMapping, nullableAncestor = false)),
                extraction = ColumnExtraction.JsonValue(parent.source, name),
                placement = ColumnPlacement.SELECT,
            )
        )
        if (type.requiresRawCompanion()) {
            draft.columns.add(rawCompanionColumn(name, path, targetName, parent.source))
        }
    }

    private fun collectObjectCollection(
        parent: PlanningNode,
        name: String,
        path: String,
        targetName: String,
        type: ResolvedType,
        elementType: ResolvedType,
        draft: ViewDraft,
    ) {
        val cursorReference = ColumnReference.Alias(cursorTargetName(targetName))
        val elementReference = ColumnReference.Alias(targetName)
        val collectionPointer = parent.pointer + JsonPointerSegment.Property(encodePointerSegment(name))
        val elementPointer = collectionPointer + JsonPointerSegment.Index(cursorReference)
        draft.columns.add(rawObjectArrayColumn(name, path, targetName, parent.source))
        if (type.requiresRawCompanion()) {
            draft.columns.add(
                rawCompanionColumn(
                    name = name,
                    path = path,
                    targetName = targetName,
                    source = parent.source,
                    inherited = false,
                )
            )
        }
        draft.collectionRequests.add(
            CollectionRequest(
                name = name,
                path = path,
                source = parent.source,
                targetName = targetName,
                tableSuffix = relativeTargetName(draft.anchorTargetName, targetName),
                cursor = CollectionCursorPlan(
                    source = parent.source,
                    property = name,
                    cursor = cursorReference,
                    element = elementReference,
                ),
                pointer = elementPointer,
                elementNode = PlanningNode(
                    path = path,
                    pointer = elementPointer,
                    source = elementReference,
                    targetName = targetName,
                    type = elementType,
                    depth = parent.depth + 1,
                    nullableAncestor = parent.nullableAncestor ||
                        type.requiresRawCompanion() ||
                        elementType.requiresRawCompanion(),
                ),
                rawElementCompanion = elementType.requiresRawCompanion(),
            )
        )
    }

    private fun collectDepthFallback(
        name: String,
        path: String,
        targetName: String,
        source: ColumnReference,
        type: ResolvedType,
        draft: ViewDraft,
        context: PlanningContext,
    ) {
        draft.columns.add(rawValueColumn(name, path, targetName, source))
        val sourceType = type.javaType.toCanonical()
        context.diagnostics.add(
            BiScriptDiagnostic(
                code = BiScriptDiagnosticCode.MAX_DEPTH_REACHED,
                aggregate = context.aggregate,
                path = path,
                sourceType = sourceType,
                decision = BiScriptMappingDecision.MAX_DEPTH_RAW_JSON,
                message = "Max expansion depth reached at [$path] with type [$sourceType]; " +
                    "the value is projected as scoped JSON and remains authoritative in __state.",
            )
        )
    }

    private fun collectOpaqueNode(
        node: PlanningNode,
        draft: ViewDraft,
        context: PlanningContext,
    ) {
        val path = node.path.ifBlank { ROOT_PATH }
        val sourceType = node.type.javaType.toCanonical()
        require(context.options.unsupportedTypeStrategy == UnsupportedTypeStrategy.RAW_JSON) {
            "Unsupported property [$path] for aggregate [${context.aggregate}] with type [$sourceType]."
        }
        draft.columns.add(
            ColumnPlan(
                name = node.targetName,
                path = path,
                targetName = node.targetName,
                type = ClickHouseType.String,
                extraction = ColumnExtraction.Reference(node.source),
                placement = ColumnPlacement.SELECT,
            )
        )
        context.diagnostics.add(
            BiScriptDiagnostic(
                code = BiScriptDiagnosticCode.RAW_JSON_FALLBACK,
                aggregate = context.aggregate,
                path = path,
                sourceType = sourceType,
                decision = BiScriptMappingDecision.RAW_JSON,
                message = "Unsupported property [$path] with type [$sourceType] is projected as scoped JSON; " +
                    "the authoritative lexical value remains in __state.",
            )
        )
    }

    private fun collectRawFallback(
        name: String,
        path: String,
        targetName: String,
        source: ColumnReference,
        type: ResolvedType,
        draft: ViewDraft,
        context: PlanningContext,
    ) {
        val sourceType = type.javaType.toCanonical()
        require(context.options.unsupportedTypeStrategy == UnsupportedTypeStrategy.RAW_JSON) {
            "Unsupported property [$path] for aggregate [${context.aggregate}] with type [$sourceType]."
        }
        draft.columns.add(rawValueColumn(name, path, targetName, source))
        context.diagnostics.add(
            BiScriptDiagnostic(
                code = BiScriptDiagnosticCode.RAW_JSON_FALLBACK,
                aggregate = context.aggregate,
                path = path,
                sourceType = sourceType,
                decision = BiScriptMappingDecision.RAW_JSON,
                message = "Unsupported property [$path] with type [$sourceType] is projected as scoped JSON; " +
                    "the authoritative lexical value remains in __state.",
            )
        )
    }

    private fun rawValueColumn(
        name: String,
        path: String,
        targetName: String,
        source: ColumnReference,
    ): ColumnPlan {
        return ColumnPlan(
            name = name,
            path = path,
            targetName = targetName,
            type = ClickHouseType.String,
            extraction = ColumnExtraction.JsonRaw(source, name),
            placement = ColumnPlacement.SELECT,
        )
    }

    private fun rawObjectArrayColumn(
        name: String,
        path: String,
        targetName: String,
        source: ColumnReference,
    ): ColumnPlan {
        return ColumnPlan(
            name = name,
            path = path,
            targetName = targetName,
            type = ClickHouseType.Array(ClickHouseType.String),
            extraction = ColumnExtraction.JsonArray(source, name),
            placement = ColumnPlacement.SELECT,
            inherited = false,
        )
    }

    private fun rawCompanionColumn(
        name: String,
        path: String,
        targetName: String,
        source: ColumnReference,
        inherited: Boolean = true,
    ): ColumnPlan {
        return ColumnPlan(
            name = name,
            path = path,
            targetName = rawTargetName(targetName),
            type = ClickHouseType.String,
            extraction = ColumnExtraction.JsonRaw(source, name),
            placement = ColumnPlacement.SELECT,
            inherited = inherited,
        )
    }

    private fun validateColumnTargets(draft: ViewDraft, context: PlanningContext) {
        val reservedProperty = draft.propertyTargetNames
            .sorted()
            .firstOrNull {
                it.startsWith(RAW_TARGET_PREFIX) ||
                    it.startsWith(ExpansionViewPlan.CURSOR_TARGET_PREFIX)
            }
        require(reservedProperty == null) {
            "Reserved namespace collision for aggregate [${context.aggregate}] in view " +
                "[${draft.targetTableName}] at target [$reservedProperty]."
        }

        val duplicates = (draft.columns.map(ColumnPlan::targetName) + ExpansionViewPlan.METADATA_TARGET_NAMES)
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .sorted()
        require(duplicates.isEmpty()) {
            "Target name collision for aggregate [${context.aggregate}] in view [${draft.targetTableName}]: " +
                "duplicate target(s) ${duplicates.joinToString(prefix = "[", postfix = "]")}."
        }
    }
}

private fun ResolvedType.canRenderDirectly(): Boolean {
    if (verifiedScalarMapping() != null) {
        return true
    }
    if (javaType.isMapLikeType) {
        return JacksonWireShapeInspector.matches(this, JsonTokenShape.MAP) && hasSupportedMapShape()
    }
    if (javaType.isCollectionLikeType || javaType.isArrayType) {
        return JacksonWireShapeInspector.matches(this, JsonTokenShape.ARRAY) &&
            arguments.firstOrNull()?.verifiedScalarMapping() != null
    }
    return false
}

private fun ResolvedType.hasSupportedMapShape(): Boolean = supportedMapValueMapping() != null

private fun ResolvedType.supportedMapValueMapping(): ScalarMapping? {
    val keyType = arguments.getOrNull(0)
    if (keyType?.rawClass != String::class.java ||
        keyType.nullability != Nullability.NON_NULL ||
        keyType.verifiedScalarMapping() == null
    ) {
        return null
    }
    return arguments.getOrNull(1)?.verifiedScalarMapping()
}

private fun childPath(parentPath: String, name: String): String =
    if (parentPath.isBlank()) name else "$parentPath.$name"

private fun childTargetName(parent: PlanningNode, name: String): String {
    val propertyName = NamingConverter.PASCAL_TO_SNAKE.convert(name)
    return if (parent.depth == 0) propertyName else "${parent.targetName}__$propertyName"
}

private fun rawTargetName(targetName: String): String = "$RAW_TARGET_PREFIX$targetName"

private fun cursorTargetName(targetName: String): String =
    "${ExpansionViewPlan.CURSOR_TARGET_PREFIX}$targetName"

private fun encodePointerSegment(value: String): String =
    value.replace("~", "~0").replace("/", "~1")

private fun relativeTargetName(anchorTargetName: String, targetName: String): String =
    if (anchorTargetName == STATE_COLUMN) {
        targetName
    } else {
        targetName.removePrefix("${anchorTargetName}__")
    }

private fun <T> unmodifiableCopy(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private const val STATE_COLUMN = "state"
private const val STATE_LAST_SUFFIX = "state_last"
private const val RAW_TARGET_PREFIX = "__raw__"
private const val ROOT_PATH = "$"

private fun ResolvedType.verifiedScalarMapping(): ScalarMapping? {
    val mapping = rawClass.scalarMapping() ?: return null
    return mapping.takeIf { JacksonWireShapeInspector.matches(this, it.tokenShape) }
}

private fun ResolvedType.toScalarType(
    mapping: ScalarMapping,
    nullableAncestor: Boolean,
): ClickHouseType {
    val scalar = mapping.clickHouseType
    return if (requiresRawCompanion() || nullableAncestor) {
        ClickHouseType.Nullable(scalar)
    } else {
        scalar
    }
}

private fun ResolvedType.requiresRawCompanion(): Boolean = nullability != Nullability.NON_NULL

private fun isUnsupportedPlatformObject(type: ResolvedType): Boolean {
    val packageName = type.rawClass.packageName
    return packageName.startsWith("java.") ||
        packageName.startsWith("javax.") ||
        packageName.startsWith("kotlin.")
}

private fun ResolvedType.isOpaqueCollectionElement(): Boolean {
    return isUnsupportedPlatformObject(this) || JacksonWireShapeInspector.inspect(this) is JsonWireShape.Opaque
}

private data class PlanningNode(
    val path: String,
    val pointer: List<JsonPointerSegment>,
    val source: ColumnReference,
    val targetName: String,
    val type: ResolvedType,
    val depth: Int,
    val nullableAncestor: Boolean,
)

private data class CollectionRequest(
    val name: String,
    val path: String,
    val source: ColumnReference,
    val targetName: String,
    val tableSuffix: String,
    val cursor: CollectionCursorPlan,
    val pointer: List<JsonPointerSegment>,
    val elementNode: PlanningNode,
    val rawElementCompanion: Boolean,
)

private data class ViewDraft(
    val targetTableName: String,
    val anchorTargetName: String,
    val columns: MutableList<ColumnPlan>,
    val propertyTargetNames: MutableList<String> = mutableListOf(),
    val collectionRequests: MutableList<CollectionRequest> = mutableListOf(),
)

private class PlanningContext(
    val aggregate: String,
    val options: BiScriptOptions,
) {
    val views: MutableList<ExpansionViewPlan> = mutableListOf()
    val diagnostics: MutableList<BiScriptDiagnostic> = mutableListOf()
}
