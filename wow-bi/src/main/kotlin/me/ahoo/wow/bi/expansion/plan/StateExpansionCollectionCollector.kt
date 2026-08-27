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
import me.ahoo.wow.bi.expansion.type.ResolvedType
import me.ahoo.wow.bi.type.ClickHouseType
import me.ahoo.wow.bi.type.JsonTokenShape
import me.ahoo.wow.bi.type.ScalarMapping

internal class StateExpansionCollectionCollector(
    private val fallbackCollector: StateExpansionFallbackCollector,
) {
    fun collect(request: PropertyPlanningRequest) {
        val elementType = request.type.arguments.firstOrNull()
        if (!JacksonWireShapeInspector.matches(request.type, JsonTokenShape.ARRAY) || elementType == null) {
            fallbackCollector.collectRaw(request)
            return
        }
        val elementMapping = elementType.verifiedScalarMapping()
        if (elementMapping != null) {
            collectScalar(request, elementType, elementMapping)
            return
        }
        if (elementType.isOpaqueCollectionElement()) {
            fallbackCollector.collectRaw(request)
            return
        }
        collectObject(request, elementType)
    }

    private fun collectScalar(
        request: PropertyPlanningRequest,
        elementType: ResolvedType,
        elementMapping: ScalarMapping,
    ) {
        request.draft.columns.add(
            ColumnPlan(
                name = request.name,
                path = request.path,
                targetName = request.targetName,
                type = ClickHouseType.Array(elementType.toScalarType(elementMapping, nullableAncestor = false)),
                extraction = ColumnExtraction.JsonValue(request.parent.source, request.name),
                placement = ColumnPlacement.SELECT,
            )
        )
        if (request.type.requiresRawCompanion()) {
            request.draft.columns.add(rawCompanionColumn(request.toRawColumnRequest()))
        }
    }

    private fun collectObject(request: PropertyPlanningRequest, elementType: ResolvedType) {
        val cursorReference = ColumnReference.Alias(cursorTargetName(request.targetName))
        val elementReference = ColumnReference.Alias(request.targetName)
        val collectionPointer = request.parent.pointer +
            JsonPointerSegment.Property(encodePointerSegment(request.name))
        val elementPointer = collectionPointer + JsonPointerSegment.Index(cursorReference)
        request.draft.columns.add(rawObjectArrayColumn(request.toRawColumnRequest()))
        if (request.type.requiresRawCompanion()) {
            request.draft.columns.add(
                rawCompanionColumn(
                    request = request.toRawColumnRequest(),
                    inherited = false,
                )
            )
        }
        request.draft.collectionRequests.add(
            CollectionRequest(
                name = request.name,
                path = request.path,
                source = request.parent.source,
                targetName = request.targetName,
                tableSuffix = relativeTargetName(request.draft.anchorTargetName, request.targetName),
                cursor = CollectionCursorPlan(
                    source = request.parent.source,
                    property = request.name,
                    cursor = cursorReference,
                    element = elementReference,
                ),
                pointer = elementPointer,
                elementNode = PlanningNode(
                    path = request.path,
                    pointer = elementPointer,
                    source = elementReference,
                    targetName = request.targetName,
                    type = elementType,
                    depth = request.parent.depth + 1,
                    nullableAncestor = request.parent.nullableAncestor ||
                        request.type.requiresRawCompanion() ||
                        elementType.requiresRawCompanion(),
                ),
                rawElementCompanion = elementType.requiresRawCompanion(),
            )
        )
    }
}
