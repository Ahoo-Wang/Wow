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

import me.ahoo.wow.bi.expansion.type.JacksonWireShapeInspector
import me.ahoo.wow.bi.expansion.type.JsonWireShape
import me.ahoo.wow.bi.expansion.type.ResolvedJsonProperty
import me.ahoo.wow.bi.type.ClickHouseType
import me.ahoo.wow.bi.type.ClickHouseTypeMapping.scalarMapping
import me.ahoo.wow.bi.type.JsonTokenShape
import me.ahoo.wow.bi.type.ScalarMapping

internal class StateExpansionPropertyCollector(
    private val session: StateExpansionPlanningSession,
) {
    private val fallbackCollector = StateExpansionFallbackCollector(session)
    private val collectionCollector = StateExpansionCollectionCollector(fallbackCollector)

    fun collectObjectProperties(parent: PlanningNode, draft: ViewDraft) {
        when (val shape = JacksonWireShapeInspector.inspect(parent.type)) {
            is JsonWireShape.ExpandableObject -> collectResolvedProperties(parent, shape.properties, draft)
            is JsonWireShape.Opaque -> fallbackCollector.collectOpaque(parent, draft)
        }
    }

    private fun collectResolvedProperties(
        parent: PlanningNode,
        properties: List<ResolvedJsonProperty>,
        draft: ViewDraft,
    ) {
        properties.forEach { property -> collectProperty(parent, property, draft) }
    }

    private fun collectProperty(
        parent: PlanningNode,
        property: ResolvedJsonProperty,
        draft: ViewDraft,
    ) {
        val request = PropertyPlanningRequest(
            parent = parent,
            name = property.serializedName,
            path = childPath(parent.path, property.serializedName),
            targetName = childTargetName(parent, property.serializedName),
            type = property.type,
            draft = draft,
        )
        draft.propertyTargetNames.add(request.targetName)

        if (parent.depth + 1 > session.options.maxExpansionDepth && !request.type.canRenderDirectly()) {
            fallbackCollector.collectDepth(request)
            return
        }
        if (collectScalarProperty(request)) {
            return
        }
        when {
            request.type.javaType.isMapLikeType -> collectMap(request)
            request.type.javaType.isCollectionLikeType || request.type.javaType.isArrayType ->
                collectionCollector.collect(request)

            isUnsupportedPlatformObject(request.type) -> fallbackCollector.collectRaw(request)
            else -> collectObjectProperty(request)
        }
    }

    private fun collectScalarProperty(request: PropertyPlanningRequest): Boolean {
        val scalarMapping = request.type.rawClass.scalarMapping() ?: return false
        if (JacksonWireShapeInspector.matches(request.type, scalarMapping.tokenShape)) {
            collectScalar(request, scalarMapping)
        } else {
            fallbackCollector.collectRaw(request)
        }
        return true
    }

    private fun collectObjectProperty(request: PropertyPlanningRequest) {
        when (val shape = JacksonWireShapeInspector.inspect(request.type)) {
            is JsonWireShape.ExpandableObject -> collectNestedObject(request, shape.properties)
            is JsonWireShape.Opaque -> fallbackCollector.collectRaw(request)
        }
    }

    private fun collectScalar(request: PropertyPlanningRequest, scalarMapping: ScalarMapping) {
        request.draft.columns.add(
            ColumnPlan(
                name = request.name,
                path = request.path,
                targetName = request.targetName,
                type = request.type.toScalarType(scalarMapping, request.parent.nullableAncestor),
                extraction = ColumnExtraction.JsonValue(request.parent.source, request.name),
                placement = ColumnPlacement.SELECT,
            )
        )
        if (request.type.requiresRawCompanion()) {
            request.draft.columns.add(rawCompanionColumn(request.toRawColumnRequest()))
        }
    }

    private fun collectNestedObject(
        request: PropertyPlanningRequest,
        properties: List<ResolvedJsonProperty>,
    ) {
        request.draft.columns.add(
            ColumnPlan(
                name = request.name,
                path = request.path,
                targetName = request.targetName,
                type = ClickHouseType.String,
                extraction = ColumnExtraction.JsonRaw(request.parent.source, request.name),
                placement = ColumnPlacement.WITH,
            )
        )
        if (request.type.requiresRawCompanion()) {
            request.draft.columns.add(rawCompanionColumn(request.toRawColumnRequest()))
        }
        collectResolvedProperties(
            parent = PlanningNode(
                path = request.path,
                pointer = request.parent.pointer +
                    JsonPointerSegment.Property(encodePointerSegment(request.name)),
                source = ColumnReference.Alias(request.targetName),
                targetName = request.targetName,
                type = request.type,
                depth = request.parent.depth + 1,
                nullableAncestor = request.parent.nullableAncestor || request.type.requiresRawCompanion(),
            ),
            properties = properties,
            draft = request.draft,
        )
    }

    private fun collectMap(request: PropertyPlanningRequest) {
        val valueType = request.type.arguments.getOrNull(1)
        val valueMapping = request.type.supportedMapValueMapping()
        if (!JacksonWireShapeInspector.matches(request.type, JsonTokenShape.MAP) ||
            valueType == null ||
            valueMapping == null
        ) {
            fallbackCollector.collectRaw(request)
            return
        }
        request.draft.columns.add(
            ColumnPlan(
                name = request.name,
                path = request.path,
                targetName = request.targetName,
                type = ClickHouseType.Map(
                    keyType = ClickHouseType.String,
                    valueType = valueType.toScalarType(valueMapping, nullableAncestor = false),
                ),
                extraction = ColumnExtraction.JsonValue(request.parent.source, request.name),
                placement = ColumnPlacement.SELECT,
            )
        )
        if (request.type.requiresRawCompanion()) {
            request.draft.columns.add(rawCompanionColumn(request.toRawColumnRequest()))
        }
    }
}
