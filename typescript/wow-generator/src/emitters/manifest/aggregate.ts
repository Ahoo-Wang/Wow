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

import type { ModuleBuilder, ModuleSet } from '../../emit/moduleBuilder';
import type { AggregateModel, ContextModel } from '../../manifest/model';
import { quoteStringLiteral, resolvePropertyName } from '../../naming/naming';
import { errorsMember, errorsOf } from './contract';
import {
  addStatement,
  doc,
  importTypes,
  type TypeFiles,
  union,
  WOW_CLIENT,
} from './generatedModule';
import { textNumbersMember } from './textNumbers';

export function emitAggregate(
  aggregate: AggregateModel,
  context: ContextModel,
  modules: ModuleSet,
  files: TypeFiles,
  outputDir: string,
): void {
  const module = modules.module(`${aggregate.directory}/aggregate.ts`);
  const name = aggregate.typeName;
  addStatement(
    module,
    `${doc([
      `The aggregate \`${aggregate.context}.${aggregate.name}\`.`,
      aggregate.description,
    ])}export const ${name}Aggregate = {\n  context: ${quoteStringLiteral(context.name)},\n  name: ${quoteStringLiteral(aggregate.name)},\n  path: ${quoteStringLiteral(`/${context.name}/${aggregate.name}`)},\n  ownership: ${quoteStringLiteral(aggregate.ownership)},\n${aggregate.idSource === 'assigned' ? '' : `  idSource: ${quoteStringLiteral(aggregate.idSource)},\n`}  snapshot: ${aggregate.snapshot},\n${textNumbersMember(aggregate, { module, outputDir, client: WOW_CLIENT })}${errorsMember(errorsOf(context, aggregate), '  ')}} as const;`,
  );
  addStatement(
    module,
    `${doc([
      `The \`state.*\` paths of \`${aggregate.name}\`'s snapshots a query filters, sorts and projects on.`,
    ])}export type ${name}StateField = ${union(aggregate.stateFields.map(quoteStringLiteral))};`,
  );
  emitAccess(aggregate, module);
  const events = aggregate.events.map(
    event =>
      `CommittedEvent<${quoteStringLiteral(event.wireName)}, ${event.type}>`,
  );
  module.imports.add(
    WOW_CLIENT,
    events.length > 0 ? ['CommittedEvent', 'SystemEvent'] : ['SystemEvent'],
  );
  addStatement(
    module,
    `${doc([
      `The events of \`${aggregate.name}\`, named as the server sends them, \`${aggregate.context}.${aggregate.name}.<Event>\` at its current revision (the \`events\` query upgrades older records), and the system events every aggregate has.`,
    ])}export type ${name}Event = ${union([...events, 'SystemEvent'])};`,
  );
  importTypes(
    module,
    outputDir,
    files,
    aggregate.events.map(event => event.type),
  );
}

/**
 * The `<Aggregate>Access` type of an aggregate: its access attributes by name
 * as its records carry them, or `NoAccess` when it declares none. A type
 * alias, not an interface: an object type alias has an implicit index
 * signature, so a record typed by it is also the untyped `Snapshot<S>`.
 */
function emitAccess(aggregate: AggregateModel, module: ModuleBuilder): void {
  const name = aggregate.typeName;
  const subject = `\`${aggregate.context}.${aggregate.name}\``;
  if (aggregate.access.length === 0) {
    module.imports.add(WOW_CLIENT, ['NoAccess']);
    addStatement(
      module,
      `${doc([`The access attributes of ${subject}: it declares none.`])}export type ${name}Access = NoAccess;`,
    );
    return;
  }
  const members = aggregate.access.map(attribute => {
    const item = attribute.type.includes('|')
      ? `(${attribute.type})`
      : attribute.type;
    const value = attribute.multiple
      ? `readonly ${item}[]`
      : `${attribute.type} | null`;
    return `  readonly ${resolvePropertyName(attribute.name)}?: ${value};`;
  });
  addStatement(
    module,
    `${doc([
      `The access attributes of ${subject} (\`@Access\`) by name, as its records carry them: a single one's value or null, a multiple one's values. A record written before an attribute was declared lacks it.`,
    ])}export type ${name}Access = {\n${members.join('\n')}\n};`,
  );
}
