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

import type {
  EnumDeclarationStructure,
  TypeAliasDeclarationStructure,
  VariableStatementStructure,
} from 'ts-morph';
import { StructureKind, VariableDeclarationKind } from 'ts-morph';
import type { AggregateModel } from '../analysis/model';
import {
  addImport,
  addImportBoundedContext,
  addImportRefModel,
} from '../emit/imports';
import { membersWithTrailingComma } from '../emit/moduleBuilder';
import { quoteStringLiteral } from '../naming/naming';
import { IMPORT_WOW_PATH } from '../wow/conventions';
import type { EmitTarget } from './target';

const DEFAULT_QUERY_CLIENT_OPTIONS = 'DEFAULT_QUERY_CLIENT_OPTIONS';

/**
 * Writes the query client of an aggregate: the enum of its event titles, the
 * union of its event types, and the factory of its snapshot and event
 * stream query clients.
 *
 * @param aggregate - The aggregate
 * @param target - Where it is written
 */
export function emitQueryClient(
  aggregate: AggregateModel,
  target: EmitTarget,
): void {
  const client = aggregate.queryClient;
  const module = target.modules.module(client.file);
  addImport(module, IMPORT_WOW_PATH, [
    'QueryClientFactory',
    'QueryClientOptions',
    'ResourceAttributionPathSpec',
  ]);
  addImportBoundedContext(
    module,
    target.outputDir,
    aggregate.context.alias,
    aggregate.context.constantName,
  );
  module.add<VariableStatementStructure>({
    kind: StructureKind.VariableStatement,
    declarationKind: VariableDeclarationKind.Const,
    declarations: [
      {
        name: DEFAULT_QUERY_CLIENT_OPTIONS,
        type: 'QueryClientOptions',
        initializer: `{
        contextAlias: ${aggregate.context.constantName},
        aggregateName: ${quoteStringLiteral(client.resourceName)},
        resourceAttribution: ${client.resourceAttribution},
      }`,
      },
    ],
    isExported: false,
  });
  module.add<EnumDeclarationStructure>({
    kind: StructureKind.Enum,
    name: client.eventTitlesName,
    isExported: true,
    members: membersWithTrailingComma(
      client.events.map(event => ({
        name: event.memberName,
        initializer: quoteStringLiteral(event.title),
      })),
    ),
  });
  for (const event of client.events) {
    addImportRefModel(module, target.outputDir, event.body);
  }
  module.add<TypeAliasDeclarationStructure>({
    kind: StructureKind.TypeAlias,
    isExported: true,
    name: client.eventTypeName,
    type: client.events.map(event => event.body.name).join(' | ') || 'never',
  });
  addImportRefModel(module, target.outputDir, client.state);
  addImportRefModel(module, target.outputDir, client.fields);
  module.add<VariableStatementStructure>({
    kind: StructureKind.VariableStatement,
    declarationKind: VariableDeclarationKind.Const,
    declarations: [
      {
        name: client.factoryName,
        // `${Fields}` is the union of the field enum's values: a field is
        // named by its enum member or by its string, and a misspelt one does
        // not compile.
        initializer: `new QueryClientFactory<${client.state.name}, \`\${${client.fields.name}}\`, ${client.eventTypeName}>(${DEFAULT_QUERY_CLIENT_OPTIONS})`,
      },
    ],
    isExported: true,
  });
}
