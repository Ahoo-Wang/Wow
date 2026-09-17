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

import { createFieldKindRegistry, type FieldKind } from '../fieldKind.js';
import { arrayFieldKind } from './array.js';
import { booleanFieldKind } from './boolean.js';
import { dateFieldKind, dateTimeFieldKind } from './dateTime.js';
import { elementMatchFieldKind } from './elementMatch.js';
import { enumFieldKind } from './enum.js';
import { METADATA_FIELD_KINDS } from './metadata.js';
import { numberFieldKind } from './number.js';
import { referenceFieldKind } from './reference.js';
import { searchFieldKind } from './search.js';
import { stringFieldKind } from './string.js';

export * from './boolean.js';
export * from './dateTime.js';
export * from './array.js';
export * from './elementMatch.js';
export * from './enum.js';
export * from './metadata.js';
export * from './number.js';
export * from './presence.js';
export * from './reference.js';
export * from './search.js';
export * from './string.js';

/** The kinds this package ships, in the order `FieldKindId` lists them. */
export const BUILTIN_FIELD_KINDS: readonly FieldKind[] = [
  stringFieldKind,
  numberFieldKind,
  booleanFieldKind,
  dateFieldKind,
  dateTimeFieldKind,
  enumFieldKind,
  referenceFieldKind,
  arrayFieldKind,
  elementMatchFieldKind,
  searchFieldKind,
  ...METADATA_FIELD_KINDS,
];

/** Ready-to-use registry; extend it with `withFieldKinds`. */
export const builtinFieldKinds = createFieldKindRegistry(BUILTIN_FIELD_KINDS);
