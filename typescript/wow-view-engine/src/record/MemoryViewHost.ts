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

import {
  StatefulViewHost,
  type StatefulViewHostOptions,
} from './StatefulViewHost.js';
import { ViewServiceError } from './viewServiceContract.js';
import { message } from '../lib/snapshot.js';

export interface MemoryViewHostOptions extends StatefulViewHostOptions {
  /** Share this Map between hosts that represent the same in-process service. */
  store?: Map<string, string | null>;
}

/** In-process view service for memory examples and Node HTTP fixtures. No browser globals or persistence. */
export class MemoryViewHost extends StatefulViewHost {
  constructor({ store = new Map(), ...options }: MemoryViewHostOptions) {
    super(options, async (key, change, signal) => {
      signal?.throwIfAborted();
      let raw: string | null | undefined;
      try {
        raw = store.get(key);
      } catch (error) {
        throw new ViewServiceError('UNAVAILABLE', message(error));
      }
      // No await inside the read/change/write critical section: a JS turn is exclusive for this Map.
      const next = change(raw);
      if (next.value !== undefined) {
        try {
          store.set(key, next.value);
        } catch (error) {
          throw new ViewServiceError('UNAVAILABLE', message(error));
        }
      }
      return next.result;
    });
  }
}
