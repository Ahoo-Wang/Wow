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
import { errorConstantName } from '../../manifest/names';
import { quoteStringLiteral } from '../../naming/naming';
import { addStatement, doc, union, WOW_CLIENT } from './generatedModule';

/** An error of a context's Manifest, by the constant it is named by. */
type ErrorEntry = ContextModel['errors'][number];

/**
 * The local name of `code` when it is a code of `aggregate` of `context`
 * (`context.aggregate.name`, with no further dot), as the Kotlin contract's
 * `aggregateCodeOf` reads it; `undefined` otherwise.
 */
function aggregateCodeOf(
  context: ContextModel,
  aggregate: AggregateModel,
  code: string,
): string | undefined {
  const prefix = `${context.name}.${aggregate.name}.`;
  if (!code.startsWith(prefix)) return undefined;
  const local = code.slice(prefix.length);
  return local.includes('.') ? undefined : local;
}

/**
 * The errors of `aggregate`, its codes (`context.aggregate.name`), or,
 * without one, those the context shares: each by its constant, the name of
 * what follows its aggregate or its context ({@link errorConstantName}). Of
 * codes whose constants coincide (`wrong-amount` and `wrong_amount`), the
 * first keeps it and the others have none, as the Kotlin contract keeps them
 * (`distinctBy`); each is still among the context's error codes.
 */
export function errorsOf(
  context: ContextModel,
  aggregate?: AggregateModel,
): { readonly member: string; readonly error: ErrorEntry }[] {
  const contextPrefix = `${context.name}.`;
  const named = context.errors.flatMap(error => {
    if (aggregate !== undefined) {
      const local = aggregateCodeOf(context, aggregate, error.code);
      return local === undefined ? [] : [{ local, error }];
    }
    if (
      context.aggregates.some(
        each => aggregateCodeOf(context, each, error.code) !== undefined,
      )
    ) {
      return [];
    }
    const local = error.code.startsWith(contextPrefix)
      ? error.code.slice(contextPrefix.length)
      : error.code;
    return [{ local, error }];
  });
  const members = new Set<string>();
  return named.flatMap(({ local, error }) => {
    const member = errorConstantName(local) || 'ERROR';
    if (members.has(member)) return [];
    members.add(member);
    return [{ member, error }];
  });
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

/**
 * The members a context's contract declares besides its aggregates, as the
 * Kotlin contract's object declares them: its name, its shared codes and its
 * hops (`Wait`).
 */
const CONTRACT_MEMBERS: ReadonlySet<string> = new Set([
  'NAME',
  'Errors',
  'Wait',
]);

/**
 * The member of `aggregate` in its context's contract: its type name, as the
 * Kotlin contract's object is named, or, for a name the contract's own
 * members take (an aggregate `errors` or `wait`), its constant's,
 * `ErrorsAggregate` or `WaitAggregate`, as the Kotlin contract names it (#6945).
 */
function contractMember(aggregate: AggregateModel): string {
  return CONTRACT_MEMBERS.has(aggregate.typeName)
    ? `${aggregate.typeName}Aggregate`
    : aggregate.typeName;
}

export function emitContract(
  context: ContextModel,
  modules: ModuleSet,
  outputDir: string,
): void {
  const module = modules.module(`${context.directory}/contract.ts`);
  const contract = `${context.typeName}Contract`;
  module.imports.add(WOW_CLIENT, ['Hop']);
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
    aggregate =>
      `  ${contractMember(aggregate)}: ${aggregate.typeName}Aggregate,\n`,
  );
  const hasSubscriptions = context.subscriptions.length > 0;
  const name = `${contract}.NAME`;
  const hops = [
    `${doc([`Until a command of \`${context.name}\` passed admission.`], '    ')}    accepted: (): Hop => Hop.accepted(${name}),`,
    `${doc([`Until a command's events are appended to the log of \`${context.name}\`.`], '    ')}    committed: (): Hop => Hop.committed(${name}),`,
    `${doc([`Until the snapshot of \`${context.name}\` holds the commit.`], '    ')}    snapshot: (): Hop => Hop.snapshot(${name}),`,
    `${doc([`Until the store the snapshot queries of \`${context.name}\` read holds the commit.`], '    ')}    queryable: (): Hop => Hop.queryable(${name}),`,
  ];
  if (hasSubscriptions) {
    hops.push(
      `${doc([`Until a subscription of \`${context.name}\` handled the commit.`], '    ')}    handled: (subscription: ${context.typeName}Subscription): Hop => Hop.handled(subscription, ${name}),`,
    );
  }
  const wait = `${doc([`The hops of a wait chain (\`Wow-Wait\`) in \`${context.name}\`, as the Kotlin contract's \`Wait\` holds them. \`WaitPlan.of(...)\` chains them, those of other contexts too.`], '  ')}  Wait: {\n${hops.join('\n')}\n  },\n`;
  addStatement(
    module,
    `${doc([
      `The bounded context \`${context.name}\`: its name, its error codes, its wait hops and its aggregates, as the Kotlin \`${contract}\` names them. A problem's \`code\` is one of its \`Errors\`, or of an aggregate's.`,
      context.description,
    ])}export const ${contract} = {\n  NAME: ${quoteStringLiteral(context.name)},\n${errorsMember(errorsOf(context), '  ')}${wait}${aggregates.join('')}} as const;`,
  );
  addStatement(
    module,
    `${doc([`An error code of \`${context.name}\`, of the context or of one of its aggregates.`])}export type ${context.typeName}ErrorCode = ${union(context.errors.map(error => quoteStringLiteral(error.code)))};`,
  );
  if (hasSubscriptions) {
    addStatement(
      module,
      `${doc([`The subscriptions of \`${context.name}\` a \`handled\` hop names.`])}export type ${context.typeName}Subscription = ${union(context.subscriptions.map(quoteStringLiteral))};`,
    );
  }
}
