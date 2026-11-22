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

import { join } from 'node:path';
import { relativeModuleSpecifier } from '../../emit/imports';
import type { ModuleSet } from '../../emit/moduleBuilder';
import type { AggregateModel, ContextModel } from '../../manifest/model';
import {
  camelCase,
  quoteStringLiteral,
  upperSnakeCase,
} from '../../naming/naming';
import { addStatement, doc, union, WOW_CLIENT } from './generatedModule';

/** An error of a context's Manifest, by the constant it is named by. */
type ErrorEntry = ContextModel['errors'][number];

/**
 * The errors of `aggregate`, those whose code is `context.aggregate.*`, or,
 * without one, the context's own: each by the constant naming what follows
 * its owner's name, as the Kotlin contract names them.
 */
export function errorsOf(
  context: ContextModel,
  aggregate?: AggregateModel,
): { readonly member: string; readonly error: ErrorEntry }[] {
  const owners = context.aggregates.map(
    each => `${context.name}.${each.name}.`,
  );
  const prefix =
    aggregate === undefined
      ? `${context.name}.`
      : `${context.name}.${aggregate.name}.`;
  return context.errors
    .filter(error =>
      aggregate === undefined
        ? !owners.some(owner => error.code.startsWith(owner))
        : error.code.startsWith(prefix),
    )
    .map(error => ({
      member:
        upperSnakeCase(
          (error.code.startsWith(prefix)
            ? error.code.slice(prefix.length)
            : error.code
          ).split(/[^A-Za-z0-9]+/),
        ) || 'ERROR',
      error,
    }));
}

/** The `Errors` member of a contract constant, or nothing when it has none. */
export function errorsMember(
  errors: ReturnType<typeof errorsOf>,
  indent: string,
): string {
  if (errors.length === 0) return '';
  const members = errors.map(
    ({ member, error }) =>
      `${doc([`${error.status}: ${error.description ?? error.code}`], `${indent}  `)}${indent}  ${member}: ${quoteStringLiteral(error.code)},`,
  );
  return `${indent}Errors: {\n${members.join('\n')}\n${indent}},\n`;
}

export function emitContract(
  context: ContextModel,
  modules: ModuleSet,
  outputDir: string,
): void {
  const module = modules.module(`${context.directory}/contract.ts`);
  const contract = `${context.typeName}Contract`;
  module.imports.add(WOW_CLIENT, ['Hop', 'wait']);
  for (const aggregate of context.aggregates) {
    module.imports.add(
      relativeModuleSpecifier(
        module.directoryPath,
        join(outputDir, aggregate.directory, 'aggregate.ts'),
      ),
      [`${aggregate.typeName}Aggregate`],
    );
  }
  const aggregates = context.aggregates.map(
    aggregate => `  ${aggregate.typeName}: ${aggregate.typeName}Aggregate,\n`,
  );
  addStatement(
    module,
    `${doc([
      `The bounded context \`${context.name}\`: its name, its error codes and its aggregates, as the Kotlin \`${contract}\` names them. A problem's \`code\` is one of its \`Errors\`, or of an aggregate's.`,
      context.description,
    ])}export const ${contract} = {\n  NAME: ${quoteStringLiteral(context.name)},\n${errorsMember(errorsOf(context), '  ')}${aggregates.join('')}} as const;`,
  );
  addStatement(
    module,
    `${doc([`An error code of \`${context.name}\`, of the context or of one of its aggregates.`])}export type ${context.typeName}ErrorCode = ${union(context.errors.map(error => quoteStringLiteral(error.code)))};`,
  );
  const hasSubscriptions = context.subscriptions.length > 0;
  if (hasSubscriptions) {
    addStatement(
      module,
      `${doc([`The subscriptions of \`${context.name}\` a \`handled\` hop names.`])}export type ${context.typeName}Subscription = ${union(context.subscriptions.map(quoteStringLiteral))};`,
    );
  }
  const name = `${contract}.NAME`;
  const hops = [
    `  /** Until a command of \`${context.name}\` passed admission. */\n  accepted: (): Hop => wait.accepted(${name}),`,
    `  /** Until a command's events are appended to the log of \`${context.name}\`. */\n  committed: (): Hop => wait.committed(${name}),`,
    `  /** Until the snapshot of \`${context.name}\` holds the commit. */\n  snapshot: (): Hop => wait.snapshot(${name}),`,
    `  /** Until the store the snapshot queries of \`${context.name}\` read holds the commit. */\n  queryable: (): Hop => wait.queryable(${name}),`,
  ];
  if (hasSubscriptions) {
    hops.push(
      `  /** Until a subscription of \`${context.name}\` handled the commit. */\n  handled: (subscription: ${context.typeName}Subscription): Hop => wait.handled(subscription, ${name}),`,
    );
  }
  addStatement(
    module,
    `${doc([`The hops of a wait chain (\`Wow-Wait\`) in \`${context.name}\`.`])}export const ${camelCase(context.name)}Wait = {\n${hops.join('\n')}\n} as const;`,
  );
}
