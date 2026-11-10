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

package me.ahoo.wow.compiler.model

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/**
 * Creates the [ModelSymbolProcessor] of a module's KSP run, as its options say.
 *
 * It does not opt the processor in to KSP's new features (backing fields and context parameters,
 * `SymbolProcessorEnvironment.registerProcessorForNewFeatures`), for which KSP 2.3 warns that the processor "has not
 * opted in for upcoming features yet". Opted in, KSP gives a property's `@field:` annotations (`@field:AggregateId`,
 * `@field:JsonProperty`) to its backing field and no longer to the property, so the compiler would read them there;
 * but KSP 2.3.13 fails with an internal error ("Unexpected null backing field symbol") when it is asked for the
 * backing field of a property a class inherits from a library, and leaving such properties out loses their annotations.
 * The processor opts in when KSP reads those backing fields, and its symbols are validated without the new features
 * ([me.ahoo.wow.compiler.model.symbol.isValid]) until then.
 */
class ModelSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        val mode = when (environment.options[OPTION]) {
            "generate" -> ModelSymbolProcessor.Mode.GENERATE
            "report" -> ModelSymbolProcessor.Mode.REPORT
            else -> ModelSymbolProcessor.Mode.OFF
        }
        val manifest = ModelSymbolProcessor.ManifestOptions(
            module = environment.options[MODULE_OPTION]?.ifBlank { null },
            context = environment.options[CONTEXT_OPTION]?.ifBlank { null },
        )
        return ModelSymbolProcessor(mode, environment.codeGenerator, environment.logger, manifest)
    }

    internal companion object {
        /** The KSP option: `generate`, `report`, or absent for nothing. */
        const val OPTION = "wow.model"

        /** The KSP option naming the module's Manifest fragment, `META-INF/wow/manifest/<module>.json`. */
        internal const val MODULE_OPTION = "wow.manifest.module"

        /** The KSP option naming the bounded context of the module's aggregates and subscriptions. */
        internal const val CONTEXT_OPTION = "wow.context"
    }
}
