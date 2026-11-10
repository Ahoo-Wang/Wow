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
/**
 * The exact numbers of a Manifest's clients as TypeScript: the `textNumbers`
 * of an aggregate's constant and of a context's service routes, and the
 * `textNumbers.ts` that declares the marks of a context's recursive types.
 */

import { basename, join } from 'node:path';
import { relativeModuleSpecifier } from '../../emit/imports';
import type { ModuleBuilder, ModuleSet } from '../../emit/moduleBuilder';
import type { AggregateModel, ContextModel } from '../../manifest/model';
import type { TextNumbersModel } from '../../manifest/textNumbers';
import { quoteStringLiteral, resolvePropertyName } from '../../naming/naming';

/** Where the marks of a module go: the module, the output directory, and the client's entry. */
interface Target {
  readonly module: ModuleBuilder;
  readonly outputDir: string;
  readonly client: string;
}

/**
 * A TypeScript literal of `marks`, importing what it uses into the module:
 * `{ price: true, lines: { '*': { amount: true } } }`, `() => treeNumbers`,
 * `textNumberOr(…)`, `textNumbersBy('kind', { … })`.
 */
function literal(marks: TextNumbersModel, target: Target): string {
  if (marks === true) return 'true';
  if ('members' in marks) return entries(marks.members, target);
  if ('lazy' in marks) {
    const specifier = relativeModuleSpecifier(
      target.module.directoryPath,
      join(target.outputDir, marks.lazy.file),
    );
    const own = `./${basename(target.module.sourceFile.getFilePath(), '.ts')}.js`;
    if (specifier !== own)
      target.module.imports.add(specifier, [marks.lazy.name]);
    return `() => ${marks.lazy.name}`;
  }
  if ('or' in marks) {
    target.module.imports.add(target.client, ['textNumberOr']);
    return `textNumberOr(${literal(marks.or, target)})`;
  }
  target.module.imports.add(target.client, ['textNumbersBy']);
  return `textNumbersBy(${quoteStringLiteral(marks.by)}, ${entries(marks.cases, target)})`;
}

/** An object literal of marks by name. */
function entries(
  marks: Readonly<Record<string, TextNumbersModel>>,
  target: Target,
): string {
  return `{ ${Object.entries(marks)
    .map(
      ([name, mark]) =>
        `${resolvePropertyName(name)}: ${literal(mark, target)}`,
    )
    .join(', ')} }`;
}

/**
 * The `textNumbers` member of an aggregate constant, or nothing when it has
 * none.
 */
export function textNumbersMember(
  aggregate: AggregateModel,
  target: Target,
): string {
  const numbers = aggregate.textNumbers;
  if (numbers === undefined) return '';
  const groups = (['state', 'events', 'commands', 'access'] as const).flatMap(
    group => {
      const value = numbers[group];
      if (value === undefined) return [];
      if (group === 'state' || group === 'access') {
        return [`    ${group}: ${literal(value as TextNumbersModel, target)},`];
      }
      const marks = Object.entries(
        value as Readonly<Record<string, TextNumbersModel>>,
      ).map(
        ([name, mark]) =>
          `      ${resolvePropertyName(name)}: ${literal(mark, target)},`,
      );
      return [`    ${group}: {\n${marks.join('\n')}\n    },`];
    },
  );
  return `  textNumbers: {\n${groups.join('\n')}\n  },\n`;
}

/**
 * The `textNumbers` option of a context's `ServiceRoutes`, by
 * `context.route`, or nothing when no route has exact numbers.
 */
export function routeNumbersOption(
  context: ContextModel,
  target: Target,
): string {
  const routes = context.routes.flatMap(route => {
    const numbers = route.textNumbers;
    if (numbers === undefined) return [];
    const sides = (['request', 'response'] as const).flatMap(side =>
      numbers[side] === undefined
        ? []
        : [`${side}: ${literal(numbers[side], target)}`],
    );
    return [
      `${quoteStringLiteral(`${context.name}.${route.name}`)}: { ${sides.join(', ')} }`,
    ];
  });
  return routes.length === 0 ? '' : `, textNumbers: { ${routes.join(', ')} }`;
}

/**
 * Writes a context's `textNumbers.ts`, the marks of its recursive types,
 * which other marks read lazily; nothing for a context that has none.
 */
export function emitTextNumbers(
  context: ContextModel,
  modules: ModuleSet,
  outputDir: string,
  client: string,
): void {
  if (context.textNumbers.length === 0) return;
  const module = modules.module(`${context.directory}/textNumbers.ts`);
  module.imports.add(client, ['TextNumbers']);
  const target = { module, outputDir, client };
  for (const declared of context.textNumbers) {
    module.add(
      `\n/** The exact numbers of \`${declared.key}\`${declared.beside ? ' with what an `allOf` says beside it' : ''}, a recursive type: its own marks, and others', read them lazily. */\nexport const ${declared.name}: TextNumbers = ${literal(declared.marks, target)};`,
    );
  }
}
