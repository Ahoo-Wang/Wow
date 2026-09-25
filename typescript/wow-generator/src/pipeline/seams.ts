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

import type { Project } from 'ts-morph';
import type { GeneratorOptions } from '../api/options';

/**
 * Test seam: an option under this key hands a generator the ts-morph project
 * to write into, such as an in-memory one, instead of one it reads from
 * `tsConfigFilePath`. A symbol the package does not export, so it is no part
 * of the public options. It lives apart from `CodeGenerator`, whose
 * declaration therefore names neither it nor ts-morph, whose major version it
 * would follow.
 */
export const PROJECT_SEAM = Symbol('project');

/**
 * The CLI's seam: an option under this key stops a run when it aborts, as
 * Ctrl-C does. A run stops at its next step; once it writes, it finishes
 * writing its files and then stops without removing anything or recording
 * the files in the manifest.
 */
export const SIGNAL_SEAM = Symbol('signal');

/**
 * What the package itself may hand a generator beyond its public options,
 * each under a symbol it does not export.
 */
export interface Seams {
  readonly [PROJECT_SEAM]?: Project;
  readonly [SIGNAL_SEAM]?: AbortSignal;
}

/** The options, with the {@link Seams} the package may add. */
export interface SeamOptions extends GeneratorOptions, Seams {}
