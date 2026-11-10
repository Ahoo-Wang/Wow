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

// The Manifests the probes of a Manifest's clients start from.

export const schema = (key: string) => `#/$defs/${key}`;

/** A Manifest with one context and one aggregate, and what they are given. */
export function manifest(
  aggregate: Record<string, unknown> = {},
  defs: Record<string, unknown> = {},
  context: Record<string, unknown> = {},
) {
  return {
    format: 1,
    service: { name: 'probe-service' },
    contexts: [
      {
        name: 'probe',
        aggregates: [
          {
            name: 'thing',
            state: { schema: schema('a.ThingState') },
            ...aggregate,
          },
        ],
        ...context,
      },
    ],
    $defs: {
      'a.ThingState': {
        type: 'object',
        properties: { n: { type: 'integer' } },
      },
      ...defs,
    },
  };
}
