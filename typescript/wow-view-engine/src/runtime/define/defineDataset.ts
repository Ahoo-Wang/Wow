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

import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
import type { DataViewDefinition } from '../../model/index.js';
import { defineView, type DefineViewOptions } from './defineView.js';

/** What a host says of a dataset beyond its descriptor. */
export interface DefineDatasetSpec {
  id: string;
  /** The key a host resolves the definition's source by. */
  source: string;
  /** The dataset's title, such as the dataset directory's. */
  title: string;
  recordNoun?: string;
}

/**
 * A dataset's definition, derived from its descriptor (host-integration.md
 * 3.2, D84): a dataset is published at run time, so its definition is not
 * code. Every field of the record the descriptor lists appears, in the
 * descriptor's order — a dataset's declaration already chose them, as a
 * column it does not declare is never exposed — labelled by its
 * description, else its path. The rest is **defineView**'s: sensitive
 * fields stay out of analyses, capabilities follow the descriptor in force,
 * and the row key is the descriptor's identity.
 *
 * The result is an ordinary definition, judged as any other when an engine
 * registers it (`ViewEngine.register`).
 */
export function defineDataset(
  descriptor: QueryModelDescriptor,
  spec: DefineDatasetSpec,
  options: DefineViewOptions = {},
): DataViewDefinition {
  const fields: Record<string, string> = {};
  for (const field of descriptor.fields)
    if (field.scope === undefined)
      fields[field.path] = field.description ?? field.path;
  return defineView(
    descriptor,
    {
      id: spec.id,
      source: spec.source,
      title: spec.title,
      ...(spec.recordNoun === undefined ? {} : { recordNoun: spec.recordNoun }),
      fields,
    },
    options,
  );
}
