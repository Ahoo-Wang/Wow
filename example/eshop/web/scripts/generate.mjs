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

// Generates the clients of the eShop's five services from their Manifest
// baselines (`example/eshop/<context>/<context>-server/manifest/baseline.json`,
// which each server's `check` holds to what it serves) into
// src/generated/<context>/, one wow-generator run per service:
//
//   pnpm --filter @ahoo-wang/wow-generator... build
//   pnpm --filter wow-eshop-web generate
//
// The output is committed byte for byte; src/generated.test.ts fails when it
// differs from what this writes.

import { execFileSync } from 'node:child_process';
import { rmSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const CONTEXTS = ['catalog', 'basket', 'ordering', 'inventory', 'payment'];

const WEB = fileURLToPath(new URL('..', import.meta.url));
export const ESHOP = join(WEB, '..');
export const CLI = join(WEB, '../../../typescript/wow-generator/dist/cli.js');

/** The Manifest baseline of the service of `context`. */
export function baselineOf(context) {
  return join(ESHOP, context, `${context}-server`, 'manifest', 'baseline.json');
}

/** Writes the clients of `context` into `output`. */
export function generate(context, output) {
  execFileSync(
    process.execPath,
    [CLI, 'generate', '-i', baselineOf(context), '-o', output, '--strict', '--quiet'],
    { stdio: 'inherit' },
  );
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  for (const context of CONTEXTS) {
    const output = join(WEB, 'src', 'generated', context);
    rmSync(output, { recursive: true, force: true });
    generate(context, output);
  }
}
