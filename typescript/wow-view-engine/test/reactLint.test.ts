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

// @vitest-environment node
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import { ESLint } from 'eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import { expect, it } from 'vitest';
const packageRoot = fileURLToPath(new URL('../', import.meta.url));
const root = resolve(packageRoot, '../..');
const local = new ESLint({ cwd: packageRoot });
const repository = new ESLint({
  cwd: root,
  overrideConfigFile: resolve(root, 'eslint.config.js'),
});
const scopes = [
  {
    name: 'package',
    lint: local,
    filePath: resolve(packageRoot, 'src/LintProbe.tsx'),
  },
  {
    name: 'root package',
    lint: repository,
    filePath: resolve(packageRoot, 'src/LintProbe.tsx'),
  },
  {
    name: 'Storybook',
    lint: repository,
    filePath: resolve(root, 'stories/view-engine/LintProbe.tsx'),
  },
];
const invalid = [
  {
    rule: 'rules-of-hooks',
    source: `import {useState} from 'react';
export function Broken({enabled}:{enabled:boolean}) {if(enabled) useState(0);return null;}`,
  },
  {
    rule: 'exhaustive-deps',
    source: `import {useEffect} from 'react';
export function Broken({value}:{value:string}) {useEffect(()=>{console.log(value)},[]);return null;}`,
  },
  {
    rule: 'immutability',
    source: `export function Broken({value}:{value:{count:number}}) {value.count++;return <span>{value.count}</span>;}`,
  },
  {
    rule: 'refs',
    source: `import {useRef} from 'react';
export function Broken() {const ref=useRef(0);return <span>{ref.current}</span>;}`,
  },
  {
    rule: 'purity',
    source: `export function Broken() {return <span>{Math.random()}</span>;}`,
  },
  {
    rule: 'static-components',
    source: `export function Broken({value}:{value:string}) {const Inner=()=> <span>{value}</span>;return <Inner/>;}`,
  },
];
it.each(scopes)(
  'enforces stable recommended rules as errors in $name',
  async ({ lint, filePath }) => {
    const config = await lint.calculateConfigForFile(filePath);
    for (const rule of Object.keys(reactHooks.configs.recommended.rules))
      expect(config.rules[rule]?.[0], rule).toBe(2);
    for (const { rule, source } of invalid) {
      const [result] = await lint.lintText(source, { filePath });
      expect(result.messages, rule).toEqual(
        expect.arrayContaining([
          expect.objectContaining({
            ruleId: `react-hooks/${rule}`,
            severity: 2,
          }),
        ]),
      );
    }
    const [valid] = await lint.lintText(
      `import {useEffect,useMemo} from 'react';
export function Valid({value}:{value:string}) {
  const snapshot=useMemo(()=>({value}),[value]);
  useEffect(()=>{console.log(snapshot.value)},[snapshot]);
  return <span>{snapshot.value}</span>;
}`,
      { filePath },
    );
    expect(valid.messages).toEqual([]);
    const [unused] = await lint.lintText(
      `// eslint-disable-next-line react-hooks/refs
export function Valid() {return null;}`,
      { filePath },
    );
    expect(unused.messages).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          severity: 2,
          message: expect.stringContaining('Unused eslint-disable directive'),
        }),
      ]),
    );
  },
);
it('keeps the React policy scoped and also covers actual CSF files', async () => {
  const core = await repository.calculateConfigForFile(
    resolve(root, 'packages/fetcher/src/fetcher.ts'),
  );
  expect(core.rules['react-hooks/rules-of-hooks']).toBeUndefined();
  const story = await repository.calculateConfigForFile(
    resolve(root, 'stories/view-engine/QuickStart.stories.tsx'),
  );
  expect(story.rules['react-hooks/exhaustive-deps'][0]).toBe(2);
});
