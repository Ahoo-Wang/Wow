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

import { Project } from 'ts-morph';
import { describe, expect, it } from 'vitest';
import { verifyGeneratedCode } from '../../src/finalize/verification';

describe('verifyGeneratedCode', () => {
  const verified = (text: string) => () => {
    const project = new Project({ useInMemoryFileSystem: true });
    verifyGeneratedCode([project.createSourceFile('/out/contract.ts', text)]);
  };

  it('refuses an object literal with a key given twice', () => {
    expect(
      verified(
        "export const Errors = {\n  WRONG_AMOUNT: 'a',\n  WRONG_AMOUNT: 'b',\n};",
      ),
    ).toThrow(
      /^The generated code does not compile; nothing was written\. This is a wow-generator bug:\n\/out\/contract\.ts:3 TS1117 /,
    );
  });

  it('passes an object literal whose keys differ', () => {
    expect(
      verified("export const Errors = {\n  A: 'a',\n  B: 'b',\n};"),
    ).not.toThrow();
  });
});
