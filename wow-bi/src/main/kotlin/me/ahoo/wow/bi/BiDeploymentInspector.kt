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

package me.ahoo.wow.bi

import reactor.core.publisher.Mono
import java.util.Collections

fun interface BiDeploymentInspector {
    val allowsDynamicScope: Boolean
        get() = false

    fun inspect(
        options: BiScriptOptions,
        operation: BiScriptOperation,
        preparation: BiScriptPreparation,
    ): Mono<BiDeploymentInspection>
}

data object NoOpBiDeploymentInspector : BiDeploymentInspector {
    override val allowsDynamicScope: Boolean = true

    override fun inspect(
        options: BiScriptOptions,
        operation: BiScriptOperation,
        preparation: BiScriptPreparation,
    ): Mono<BiDeploymentInspection> =
        Mono.just(BiDeploymentInspection.Unavailable)
}

sealed interface BiDeploymentInspection {
    data object Unavailable : BiDeploymentInspection

    /**
     * The observed catalog, verified by an inspector of this module. Only the built-in inspectors can observe one; a
     * custom [BiDeploymentInspector] may delegate to them but cannot fabricate an observation.
     */
    class Available internal constructor(
        internal val deployment: ObservedBiDeployment,
        internal val reconciliation: BiReconciliationSnapshot,
    ) : BiDeploymentInspection {
        internal constructor(deployment: ObservedBiDeployment) : this(deployment, BiReconciliationSnapshot.EMPTY)

        internal companion object {
            fun reconciled(
                deployment: ObservedBiDeployment,
                repairableComputedDrifts: List<RepairableBiObjectDrift>,
                verifiedComputedKeys: Set<BiObjectKey> = emptySet(),
            ): Available = Available(
                deployment,
                BiReconciliationSnapshot(
                    Collections.unmodifiableList(ArrayList(repairableComputedDrifts)),
                    Collections.unmodifiableSet(LinkedHashSet(verifiedComputedKeys)),
                ),
            )
        }
    }
}

internal data class BiReconciliationSnapshot(
    val repairableComputedDrifts: List<RepairableBiObjectDrift>,
    /** Computed objects whose observed SELECT and target match the requested deployment exactly. */
    val verifiedComputedKeys: Set<BiObjectKey> = emptySet(),
) {
    companion object {
        val EMPTY = BiReconciliationSnapshot(emptyList())
    }
}
