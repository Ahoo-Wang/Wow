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

import type { DeepReadonly } from '../lib/types.js';
import { cloneSnapshot } from '../lib/types.js';
import type {
  ViewDefinition,
  RecordPresentation,
  RecordPresentationDefaults,
} from '../contracts/viewModel.js';
import {
  validateRecordPresentation,
  validateRecordPresentationDefaults,
} from './validation/presentationValidation.js';

/** Resolve only the requested layout, preserving configured layouts as independent copies. */
export function resolveRecordPresentation(
  definition: DeepReadonly<ViewDefinition>,
  layout: RecordPresentation['layout'],
  existing?: DeepReadonly<RecordPresentationDefaults>,
): RecordPresentation {
  for (const input of [existing, definition.record!.defaultPresentation])
    if (input !== undefined)
      validateRecordPresentationDefaults(input, definition);
  const table =
    existing?.table ??
    (layout === 'table'
      ? (definition.record!.defaultPresentation?.table ?? {
          columns: definition.fields.map(field => ({
            id: field.field,
            kind: 'field' as const,
            field: field.field,
          })),
        })
      : undefined);
  const card =
    existing?.card ??
    (layout === 'card'
      ? (definition.record!.defaultPresentation?.card ?? {
          title: { id: 'title', field: definition.record!.rowKey },
          fields: [],
          ...(definition.record!.recordActions?.row ? { actions: {} } : {}),
        })
      : undefined);
  const result = {
    layout,
    ...(table === undefined ? {} : { table }),
    ...(card === undefined ? {} : { card }),
  };
  validateRecordPresentation(result, definition);
  return cloneSnapshot<RecordPresentation>(result);
}
