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

import { afterAll, describe, expect, it } from 'vitest';
import {
  cpSync,
  existsSync,
  readdirSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'fs';
import * as path from 'path';
import { runGenerate, SilentLogger } from '../src/utils';
import {
  BUNDLER_OPTIONS,
  coldDirectory,
  NODE_NEXT_OPTIONS,
  PACKAGE_ROOT,
  removeDirectories,
  typeCheck,
} from './support/generation';

const EXPECTED_DIR = 'expected';

/**
 * Snapshots under expected/ are the regression baseline for generated code.
 * After an INTENTIONAL generator change, regenerate them with:
 *   UPDATE_SNAPSHOTS=true pnpm --filter @ahoo-wang/wow-generator test
 * and review the diff before committing. Blindly updating snapshots defeats
 * this safety net.
 */
const UPDATE_SNAPSHOTS = process.env.UPDATE_SNAPSHOTS === 'true';

const directories: string[] = [];
afterAll(() => removeDirectories(directories));

function resolvePackagePath(...segments: string[]): string {
  return path.join(PACKAGE_ROOT, ...segments);
}

function listFilesRecursive(dir: string, base: string = dir): string[] {
  if (!existsSync(dir)) {
    return [];
  }
  const files: string[] = [];
  for (const name of readdirSync(dir)) {
    const fullPath = path.join(dir, name);
    if (statSync(fullPath).isDirectory()) {
      files.push(...listFilesRecursive(fullPath, base));
    } else {
      files.push(path.relative(base, fullPath));
    }
  }
  return files.sort();
}

function expectOutputMatchesSnapshot(outputDir: string, snapshotDir: string) {
  const absoluteSnapshotDir = resolvePackagePath(snapshotDir);

  if (UPDATE_SNAPSHOTS) {
    rmSync(absoluteSnapshotDir, { recursive: true, force: true });
    cpSync(outputDir, absoluteSnapshotDir, { recursive: true });
    return;
  }

  const expectedFiles = listFilesRecursive(absoluteSnapshotDir);
  const actualFiles = listFilesRecursive(outputDir);
  expect(
    actualFiles,
    `Generated file list diverged from [${snapshotDir}]. ` +
      'If this change is intentional, regenerate the snapshots with UPDATE_SNAPSHOTS=true and review the diff.',
  ).toEqual(expectedFiles);

  for (const file of expectedFiles) {
    const expected = readFileSync(
      path.join(absoluteSnapshotDir, file),
      'utf-8',
    );
    const actual = readFileSync(path.join(outputDir, file), 'utf-8');
    expect(
      actual,
      `Generated file [${file}] diverged from its snapshot. ` +
        'If this change is intentional, regenerate the snapshots with UPDATE_SNAPSHOTS=true and review the diff.',
    ).toBe(expected);
  }
}

/**
 * Generates into a directory outside the package, where `@ahoo-wang/*` does
 * not resolve: the output must not depend on what its directory resolves.
 * The project's tsconfig sits beside the output, as in an application.
 */
async function generate(
  name: string,
  options: { input: string; config?: string },
): Promise<string> {
  const dir = coldDirectory(`e2e-${name}`, directories);
  const tsConfigFilePath = path.join(dir, 'tsconfig.json');
  writeFileSync(
    tsConfigFilePath,
    JSON.stringify({
      compilerOptions: {
        target: 'ES2022',
        module: 'ESNext',
        moduleResolution: 'bundler',
        strict: true,
        experimentalDecorators: true,
        skipLibCheck: true,
        noEmit: true,
      },
      include: ['src/**/*'],
    }),
  );
  const output = path.join(dir, 'src', 'generated');
  const exitCode = await runGenerate(
    {
      input: resolvePackagePath(options.input),
      output,
      config: options.config && resolvePackagePath(options.config),
      tsConfigFilePath,
    },
    new SilentLogger(),
  );
  expect(exitCode).toBe(0);
  return output;
}

describe('E2E Test', () => {
  it('should generate [test/demo.spec.json] code', async () => {
    const output = await generate('demo', {
      input: 'test/demo.spec.json',
      config: 'test/wow-generator.config.json',
    });

    // Structural smoke checks on key artifacts (the snapshot comparison below
    // is the exact baseline; these guard the semantics that matter most).
    const cartApiClient = readFileSync(
      path.join(output, 'example/CartApiClient.ts'),
      'utf-8',
    );
    expect(cartApiClient).toContain('export class CartApiClient');

    const orderCommandClient = readFileSync(
      path.join(output, 'example/order/commandClient.ts'),
      'utf-8',
    );
    expect(orderCommandClient).toContain('export class OrderCommandClient');
    expect(orderCommandClient).toContain(
      'CreateOrderCommand = CommandBody<CreateOrder>',
    );
    // The order aggregate routes as sales-order, which its query paths use.
    expect(
      readFileSync(path.join(output, 'example/order/queryClient.ts'), 'utf-8'),
    ).toContain("aggregateName: 'sales-order',");

    expectOutputMatchesSnapshot(output, `${EXPECTED_DIR}/demo-spec`);
  }, 30000);

  it('should generate [test/compensation.spec.json] code', async () => {
    const output = await generate('compensation', {
      input: 'test/compensation.spec.json',
    });

    const commandClient = readFileSync(
      path.join(output, 'compensation/execution_failed/commandClient.ts'),
      'utf-8',
    );
    expect(commandClient).toContain(
      'export class ExecutionFailedCommandClient',
    );
    expect(commandClient).toContain('CommandRequest<DeleteAggregateCommand>');

    const types = readFileSync(
      path.join(output, 'compensation/execution_failed/types.ts'),
      'utf-8',
    );
    expect(types).toContain('export interface CreateExecutionFailed');

    expectOutputMatchesSnapshot(output, `${EXPECTED_DIR}/compensation-spec`);
  }, 30000);

  // The committed snapshots compile as a project using them would, with
  // both the bundler and the NodeNext module resolution.
  describe.each(['demo-spec', 'compensation-spec'])('expected/%s', snapshot => {
    it.each([
      ['bundler', BUNDLER_OPTIONS],
      ['NodeNext', NODE_NEXT_OPTIONS],
    ] as const)(
      'type-checks with %s module resolution',
      (_, options) => {
        const dir = coldDirectory(`tsc-${snapshot}`, directories);
        cpSync(resolvePackagePath(EXPECTED_DIR, snapshot), dir, {
          recursive: true,
        });
        expect(typeCheck(dir, options)).toEqual([]);
      },
      60000,
    );
  });
});
