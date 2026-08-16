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

import { describe, expect, it } from 'vitest';
import {
  cpSync,
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'fs';
import * as path from 'path';
import { generateAction } from '../src/utils';

const OUT_PUT_DIR = 'test-output';
const EXPECTED_DIR = 'expected';

/**
 * Snapshots under expected/ are the regression baseline for generated code.
 * After an INTENTIONAL generator change, regenerate them with:
 *   UPDATE_SNAPSHOTS=true pnpm --filter @ahoo-wang/fetcher-generator test
 * and review the diff before committing. Blindly updating snapshots defeats
 * this safety net.
 */
const UPDATE_SNAPSHOTS = process.env.UPDATE_SNAPSHOTS === 'true';

function resolvePackagePath(...segments: string[]): string {
  return path.join(__dirname, '..', ...segments);
}

/**
 * ts-morph's Project requires tsConfigFilePath to point at an EXISTING file.
 * (The pre-rewrite e2e test silently relied on a stale, gitignored
 * test-output/tsconfig.json left over from previous local runs — on a fresh
 * checkout the generation crashed and generateAction's catch swallowed it,
 * so the zero-assertion test appeared green.) Write a hermetic tsconfig
 * scoped to the output directory before generating.
 */
function writeOutputTsconfig(outputDir: string) {
  const absoluteOutputDir = resolvePackagePath(outputDir);
  mkdirSync(absoluteOutputDir, { recursive: true });
  writeFileSync(
    path.join(absoluteOutputDir, 'tsconfig.json'),
    JSON.stringify(
      {
        compilerOptions: {
          target: 'ES2020',
          module: 'ES2020',
          moduleResolution: 'bundler',
          strict: true,
          experimentalDecorators: true,
          emitDecoratorMetadata: true,
          skipLibCheck: true,
          noEmit: true,
        },
        include: ['./**/*'],
      },
      null,
      2,
    ),
  );
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
  const absoluteOutputDir = resolvePackagePath(outputDir);
  const absoluteSnapshotDir = resolvePackagePath(snapshotDir);

  if (UPDATE_SNAPSHOTS) {
    rmSync(absoluteSnapshotDir, { recursive: true, force: true });
    cpSync(absoluteOutputDir, absoluteSnapshotDir, { recursive: true });
    return;
  }

  const expectedFiles = listFilesRecursive(absoluteSnapshotDir);
  const actualFiles = listFilesRecursive(absoluteOutputDir);
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
    const actual = readFileSync(path.join(absoluteOutputDir, file), 'utf-8');
    expect(
      actual,
      `Generated file [${file}] diverged from its snapshot. ` +
        'If this change is intentional, regenerate the snapshots with UPDATE_SNAPSHOTS=true and review the diff.',
    ).toBe(expected);
  }
}

describe('E2E Test', () => {
  it('should generate [test/demo.spec.json] code', async () => {
    const outputDir = `${OUT_PUT_DIR}/demo`;
    rmSync(resolvePackagePath(outputDir), { recursive: true, force: true });
    writeOutputTsconfig(outputDir);

    await generateAction({
      input: 'test/demo.spec.json',
      output: outputDir,
      config: 'test/fetcher-generator.config.json',
      tsConfigFilePath: `${outputDir}/tsconfig.json`,
    });

    // Structural smoke checks on key artifacts (the snapshot comparison below
    // is the exact baseline; these guard the semantics that matter most).
    const cartApiClient = readFileSync(
      resolvePackagePath(outputDir, 'example/CartApiClient.ts'),
      'utf-8',
    );
    expect(cartApiClient).toContain('export class CartApiClient');

    const orderCommandClient = readFileSync(
      resolvePackagePath(outputDir, 'example/order/commandClient.ts'),
      'utf-8',
    );
    expect(orderCommandClient).toContain('export class OrderCommandClient');
    expect(orderCommandClient).toContain(
      'CreateOrderCommand = CommandBody<CreateOrder>',
    );

    expectOutputMatchesSnapshot(outputDir, `${EXPECTED_DIR}/demo-spec`);
  }, 15000);

  it('should generate [test/compensation.spec.json] code', async () => {
    const outputDir = `${OUT_PUT_DIR}/compensation`;
    rmSync(resolvePackagePath(outputDir), { recursive: true, force: true });

    await generateAction({
      input: 'test/compensation.spec.json',
      output: outputDir,
    });

    const commandClient = readFileSync(
      resolvePackagePath(
        outputDir,
        'compensation/execution_failed/commandClient.ts',
      ),
      'utf-8',
    );
    expect(commandClient).toContain(
      'export class ExecutionFailedCommandClient',
    );
    expect(commandClient).toContain('CommandRequest<DeleteAggregateCommand>');

    const types = readFileSync(
      resolvePackagePath(outputDir, 'compensation/execution_failed/types.ts'),
      'utf-8',
    );
    expect(types).toContain('export interface CreateExecutionFailed');

    expectOutputMatchesSnapshot(outputDir, `${EXPECTED_DIR}/compensation-spec`);
  }, 15000);
});
