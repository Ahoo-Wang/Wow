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

import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
import { runInNewContext } from 'node:vm';
import { expect, it } from 'vitest';
import { Project } from 'ts-morph';
import type { Schema } from '@ahoo-wang/fetcher-openapi';
import { TypeGenerator } from '../src/model/typeGenerator';

function generate(schemas: Record<string, Schema>, consumer: string) {
  const dir = mkdtempSync(join(tmpdir(), 'fetcher-enum-const-'));
  try {
    const project = new Project({ skipAddingFilesFromTsConfig: true });
    const file = project.createSourceFile(join(dir, 'types.ts'), '');
    for (const [name, schema] of Object.entries(schemas)) {
      new TypeGenerator(
        { name, path: '/' },
        file,
        { key: name, schema },
        dir,
      ).generate();
    }
    file.addStatements(consumer);
    project.saveSync();
    writeFileSync(
      join(dir, 'tsconfig.json'),
      JSON.stringify({
        compilerOptions: {
          strict: true,
          skipLibCheck: true,
          module: 'CommonJS',
          target: 'ES2020',
          types: [],
          outDir: 'dist',
        },
        include: ['types.ts'],
      }),
    );
    const require = createRequire(import.meta.url);
    const result = spawnSync(
      process.execPath,
      [
        require.resolve('typescript/lib/tsc.js'),
        '-p',
        join(dir, 'tsconfig.json'),
      ],
      { encoding: 'utf8' },
    );
    expect(result.status, result.stdout + result.stderr).toBe(0);
    const exports: Record<string, unknown> = {};
    runInNewContext(readFileSync(join(dir, 'dist/types.js'), 'utf8'), {
      exports,
    });
    return exports;
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

it('keeps numeric and mixed EnumText exports and mixed runtime string members', () => {
  const escaped = 'literal ` ${notInterpolation}';
  const result = generate(
    {
      Numeric: {
        type: 'integer',
        enum: [0, 1],
        'x-enum-text': { '0': escaped, '1': 'Enabled' },
      },
      EscapedString: { type: 'string', enum: [escaped] },
      Mixed: {
        enum: ['ON', 0, false],
        'x-enum-text': { ON: escaped, '0': 'Disabled' },
      },
    },
    `
  const numeric: Numeric = 0;
  const mixed: Mixed[] = [Mixed.ON,0,false];
  export const labels = [NumericEnumText.NUM_0,MixedEnumText.ON];
  export const member = Mixed.ON;
  export const escapedValues = Object.values(EscapedString);
  // @ts-expect-error Unknown numeric enum member.
  const wrongNumeric: Numeric = 2;
  // @ts-expect-error Unknown mixed enum string.
  const wrongMixed: Mixed = 'OFF';
  void numeric; void mixed; void wrongNumeric; void wrongMixed;
 `,
  );
  expect(result.labels).toEqual([escaped, escaped]);
  expect(result.escapedValues).toEqual([escaped]);
  expect(result.member).toBe('ON');
});

it('keeps empty object enum and const values narrow, including nested literals', () => {
  generate(
    {
      EmptyEnum: { enum: [{}, 'ON'] },
      EmptyConst: { const: {} },
      NestedConst: { const: { child: {}, values: [{}, 'ON'] } },
      Record: { type: 'string' },
    },
    `
  const enumValues: EmptyEnum[] = [{},EmptyEnum.ON];
  const empty: EmptyConst = {};
  const nested: NestedConst = {child:{},values:[{},'ON']};
  // @ts-expect-error Empty object enum cannot accept a number.
  const enumNumber: EmptyEnum = 42;
  // @ts-expect-error Empty object enum cannot accept a boolean.
  const enumBoolean: EmptyEnum = false;
  // @ts-expect-error Empty object enum cannot accept an array.
  const enumArray: EmptyEnum = [];
  // @ts-expect-error Empty object constant cannot accept a number.
  const constNumber: EmptyConst = 42;
  // @ts-expect-error Empty object constant cannot accept arbitrary fields.
  const constFields: EmptyConst = {x:1};
  // @ts-expect-error Nested empty constants cannot become broad object types.
  const nestedWrong: NestedConst = {child:42,values:[{},'ON']};
  void enumValues; void empty; void nested;
 `,
  );
});

it('rejects arrays matching nonempty object literals without rejecting plain objects', () => {
  generate(
    {
      LengthConst: { const: { length: 0 } },
      TupleEnum: { enum: [{ 0: 'ON', length: 1 }] },
      NestedConst: { const: { child: { length: 0 } } },
      ArrayLiteral: { const: ['ON'] },
    },
    `
  interface EmptyLength { length: 0 }
  const shape: EmptyLength = {length: 0};
  const plain: LengthConst = shape;
  const tupleShape: TupleEnum = {0: 'ON', length: 1};
  const nested: NestedConst = {child: {length: 0}};
  const array: ArrayLiteral = ['ON'];
  const mutableTuple: [] = [];
  // @ts-expect-error Array length does not make an array an object constant.
  const mutable: LengthConst = mutableTuple;
  // @ts-expect-error Readonly arrays must not satisfy object constants either.
  const readonlyArray: LengthConst = [] as const;
  // @ts-expect-error Numeric object keys must not admit matching tuples.
  const enumArray: TupleEnum = ['ON'] as const;
  // @ts-expect-error Nested object literals preserve the same boundary.
  const nestedArray: NestedConst = {child: [] as const};
  void plain; void tupleShape; void nested; void array;
 `,
  );
});

it('applies declared types alongside enum and const constraints', () => {
  generate(
    {
      TextEnum: { type: 'string', enum: ['ON', 0] },
      ImpossibleEnum: { type: 'number', enum: ['ON'] },
      NumberConst: { type: 'string', const: 1 },
      BooleanConst: { type: 'number', const: true },
      ObjectConst: { type: 'string', const: {} },
      ArrayConst: { type: 'string', const: [] },
      ObjectWithConst: {
        type: 'object',
        properties: { id: { type: 'string' } },
        const: 1,
      },
      ValidConst: { type: 'number', const: 1 },
      ArrayAsObject: { type: 'object', const: [] },
      FractionConst: { type: 'integer', const: 1.5 },
      IntegerEnum: { type: 'integer', enum: [1, 1.5] },
      ObjectEnum: { type: 'object', enum: [[], {}] },
      NullableEnum: { type: 'string', nullable: true, enum: ['ON', null] },
    },
    `
  const text: TextEnum = TextEnum.ON;
  const valid: ValidConst = 1;
  const integer: IntegerEnum = 1;
  const object: ObjectEnum = {};
  const nullable: NullableEnum[] = ['ON', null];
  // @ts-expect-error JSON objects exclude arrays even though arrays are TS objects.
  const arrayAsObject: ArrayAsObject = [];
  // @ts-expect-error Fractions do not satisfy an integer type.
  const fractionConst: FractionConst = 1.5;
  // @ts-expect-error Integer enums retain only integral values.
  const fractionEnum: IntegerEnum = 1.5;
  // @ts-expect-error Object enums do not admit array values.
  const arrayEnum: ObjectEnum = [];
  void integer; void object; void nullable;
  // @ts-expect-error The number enum member fails the string type.
  const numeric: TextEnum = 0;
  // @ts-expect-error The only enum value fails the number type.
  const impossible: ImpossibleEnum = ImpossibleEnum.ON;
  // @ts-expect-error Number constant fails declared string type.
  const numberConst: NumberConst = 1;
  // @ts-expect-error Boolean constant fails declared number type.
  const boolConst: BooleanConst = true;
  // @ts-expect-error Object constant fails declared string type.
  const objectConst: ObjectConst = {};
  // @ts-expect-error Array constant fails declared string type.
  const arrayConst: ArrayConst = [];
  // @ts-expect-error Object generation must not bypass its constant.
  const objectWithConst: ObjectWithConst = {id:'x'};
  void text; void valid;
 `,
  );
});
