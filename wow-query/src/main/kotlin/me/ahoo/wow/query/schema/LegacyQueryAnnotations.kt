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

@file:Suppress("DEPRECATION")

package me.ahoo.wow.query.schema

import me.ahoo.wow.api.query.annotation.Mask
import me.ahoo.wow.api.query.annotation.QueryTemporal
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.api.query.mask.KeepMask
import me.ahoo.wow.api.query.mask.MaskStrategy
import me.ahoo.wow.api.query.mask.Masking
import me.ahoo.wow.api.query.mask.Mask as LegacyMask
import me.ahoo.wow.api.query.schema.QueryTemporal as LegacyQueryTemporal

/*
 * compat(wow<9.2): the 9.1 query annotations, still named by domain jars compiled against 9.1. Each is read as the
 * annotation that replaced it, so such a jar keeps its masking and temporal fields on 9.2.
 */

/** This annotation as a `@QueryTemporal`: itself, or the 9.1 `schema.QueryTemporal(timeUnit)`; else `null`. */
internal fun Annotation.asQueryTemporal(): QueryTemporal? = when (this) {
    is QueryTemporal -> this
    is LegacyQueryTemporal -> QueryTemporal(unit = timeUnit)
    else -> null
}

/**
 * The rule of a 9.1 mask annotation, one whose class carries `@Masking`: `@Mask` and `@KeepMask` read as the
 * equivalent `@Sensitive(SensitivityLevel.DISPLAY)` mask, any other is masked by the strategy it names. 9.1 let
 * filters and sorts compare the raw value of a masked field, which is what [SensitivityLevel.DISPLAY] keeps.
 *
 * @throws QuerySchemaConflictException when the strategy cannot be created or fails to compile the annotation.
 */
internal fun Annotation.legacyMaskRule(): MaskRule? {
    val masking = annotationClass.java.getAnnotation(Masking::class.java) ?: return null
    return when (this) {
        is LegacyMask -> MaskRule(SensitivityLevel.DISPLAY)
        is KeepMask -> MaskRule(SensitivityLevel.DISPLAY, Mask(keepPrefix = prefix, keepSuffix = suffix))
        else -> customMaskRule(masking)
    }
}

@Suppress("UNCHECKED_CAST")
private fun Annotation.customMaskRule(masking: Masking): MaskRule {
    val type = masking.strategy
    val strategy = MaskRule.conflictOnFailure("Unable to instantiate MaskStrategy [${type.qualifiedName}].") {
        type.objectInstance ?: type.java.getConstructor().newInstance()
    }
    val compiled = MaskRule.conflictOnFailure(
        "Unable to compile mask annotation [${annotationClass.qualifiedName}] with MaskStrategy [${type.qualifiedName}].",
    ) {
        (strategy as MaskStrategy<Annotation>).compile(this)
    }
    return MaskRule.legacy(this) { value -> compiled.mask(value) }
}
