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

import type ts from 'typescript';

/** The marker a test about the spelling itself carries; see the `.mjs`. */
export const WIRE_SPELLING_MARKER: string;

/** The vocabularies of Wow's query protocol: each enum's or table's values. */
export function wireVocabularies(dsl?: string): Set<string>[];

/** The program of a tsconfig, type-checked as its own build checks it. */
export function wireProgram(tsconfig: string): ts.Program;

/** The literals of a file that are a Wow wire value by the type of their slot. */
export function wireLiterals(
  program: ts.Program,
  path: string,
  vocabularies: readonly ReadonlySet<string>[],
  options?: { honourMarker?: boolean },
): { value: string; line: number }[];
