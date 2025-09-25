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
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { existsSync } from 'fs';
import { join } from 'path';
import { rmSync, mkdirSync } from 'fs';
import { Project } from 'ts-morph';
import { CodeGenerator } from '@/index.ts';

describe('OpenAPI Generator', () => {
  const testOutputDir = join(__dirname, 'test-output');

  beforeEach(() => {
    // Clean up test output directory
    if (existsSync(testOutputDir)) {
      rmSync(testOutputDir, { recursive: true, force: true });
    }
    mkdirSync(testOutputDir, { recursive: true });
  });

  afterEach(() => {
    // Clean up after tests
    if (existsSync(testOutputDir)) {
      rmSync(testOutputDir, { recursive: true, force: true });
    }
  });

  it('should generate TypeScript code from demo OpenAPI spec', () => {
    const inputPath = join(__dirname, 'compensation-spec.json');
    const options = {
      inputPath,
      outputDir: testOutputDir,
      project: new Project(),
    };
    const codeGenerator = new CodeGenerator(options);
    expect(async () => {
      await codeGenerator.generate();
    }).not.toThrow();
  });


});
