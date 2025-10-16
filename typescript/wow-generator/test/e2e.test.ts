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

import { describe, it } from 'vitest';
import { generateAction } from '../src/utils';

const OUT_PUT_DIR = 'test-output';

function clearOutputSubDirs() {
  const fs = require('fs');
  const path = require('path');
  const outPutDir = path.join(__dirname, '..', OUT_PUT_DIR);

  try {
    if (fs.existsSync(outPutDir)) {
      const files = fs.readdirSync(outPutDir);
      for (const file of files) {
        const filePath = path.join(outPutDir, file);
        const stat = fs.statSync(filePath);
        if (stat.isDirectory()) {
          fs.rmSync(filePath, { recursive: true, force: true });
        }
      }
    }
  } catch (error) {
    console.error(`Failed to remove subdirectories in ${outPutDir}:`, error);
  }
}

describe('E2E Test', () => {
  it('should generate [test/demo-spec.json] code', async () => {
    clearOutputSubDirs();
    await generateAction({
      input: 'test/demo-spec.json',
      output: OUT_PUT_DIR,
      config: 'test/fetcher-generator.config.json',
      tsConfigFilePath: `${OUT_PUT_DIR}/tsconfig.json`,
    });
  });

  it('should generate [test/compensation-spec.json] code', async () => {
    clearOutputSubDirs();
    await generateAction({ input: 'test/compensation-spec.json', output: OUT_PUT_DIR });
  });
});