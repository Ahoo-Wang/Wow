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

import type { DatasetResource } from '../runtime/datasetResources.js';
import {
  bind,
  type ViewBinding,
  type ViewBindingOptions,
} from './workbench/bindings.js';

/**
 * How `datasetBindings` binds each dataset served: the options of its
 * binding, asked of each one.
 */
export interface DatasetBindingOptions {
  /** Where a view of `dataset` lives: the host's route of it on `instanceId`. */
  route(dataset: DatasetResource, instanceId: string | null): string;
  /** The rest of its binding, such as the actions its rows offer; none by default. */
  binding?(
    dataset: DatasetResource,
  ): Omit<ViewBindingOptions, 'route'> | undefined;
}

/**
 * A binding of each dataset served: the host spreads them into the ones
 * it hands `ViewHost`, so its navigation lists the datasets and a view of
 * one opens at its route.
 */
export function datasetBindings(
  served: readonly DatasetResource[],
  { route, binding }: DatasetBindingOptions,
): ViewBinding[] {
  return served.map(dataset =>
    bind(dataset.id, {
      ...binding?.(dataset),
      route: instanceId => route(dataset, instanceId),
    }),
  );
}
