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

import assert from 'node:assert/strict';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

const configPath = fileURLToPath(new URL('../tsconfig.json', import.meta.url));
const file = fileURLToPath(
  new URL('../src/__extension_contract__.ts', import.meta.url),
);
const source = `
  import type { RecordCardRenderContext, CellRendererProps, RecordExtensions } from './record/recordReactTypes.js';
  import type { FilterEditorProps } from './filter/filterReactTypes.js';
  import type { DeepReadonly } from './lib/types.js';
  import type { ViewEngine } from './engine/ViewEngine.js';
  import type { ViewPageProps } from './view/ViewPage.js';
  import type { FilterRegistration } from './filter/filterReactTypes.js';
  import type { ViewDefinition, ViewSource, ViewEngineOptions } from './contracts/viewModel.js';
  import type { SessionStore } from './engine/SessionStore.js';
  import type { AnalysisCompiler, AnalysisComponentCompileContext } from './analysis/analysisModel.js';
  import type { AnalysisSession } from './contracts/viewModel.js';
  import type { RecordSession, ViewEngineState } from './contracts/viewModel.js';
  const pureDefinition:ViewDefinition={id:'a',title:'A',sourceId:'a',fields:[],analysis:{count:true,fields:[]}};
  const aggregateSource:ViewSource={aggregate:async()=>[]};
  // @ts-expect-error a definition must declare at least one view capability
  const emptyDefinition:ViewDefinition={id:'a',title:'A',sourceId:'a',fields:[]};
  declare const customAnalysis: AnalysisCompiler;
  declare const componentContext: AnalysisComponentCompileContext;
  // @ts-expect-error custom analysis registrations require explicit roles
  const missingRoles: AnalysisCompiler = { compile: customAnalysis.compile };
  // @ts-expect-error role declarations are immutable
  customAnalysis.roles.push('metric');
  // @ts-expect-error the actual component role cannot be changed
  componentContext.role = 'dimension';
  declare const recordExtensions: RecordExtensions;
  // @ts-expect-error record consumers must not depend on analysis registrations
  recordExtensions.analysis;
  declare const page: ViewPageProps;
  const recordOptions: NonNullable<ViewPageProps['record']> = { selectable: true, autoRefreshPaused: true, renderToolbar: context => context.defaultContent };
  // @ts-expect-error record options are scoped, not global page policy
  page.autoRefreshPaused;
  // @ts-expect-error selection applies only to record views
  page.selectable;
  // @ts-expect-error page shell owns configuration panel coordination
  page.record!.configurationOpen;
  declare const registration: FilterRegistration;
  const extensions: ViewPageProps['extensions'] = { filters: { custom: registration } };
  const headless: ViewEngineOptions['filterCompilers'] = { custom: { compile: registration.compile } };
  // @ts-expect-error React registers compilation with its component, never through a second registry
  page.filterCompilers;
  declare const card: RecordCardRenderContext;
  // @ts-expect-error custom card records are readonly
  card.record.amount = 99;
  // @ts-expect-error cards cannot access engine internals
  card.engine;
  declare const cell: CellRendererProps;
  declare const editor: FilterEditorProps;
  const value: unknown = cell.record.amount;
  const callback: DeepReadonly<(id: string) => boolean> = id => id.length > 0;
  callback('record-id');
  declare const engine: ViewEngine;
  declare const session: RecordSession;
  declare const state: ViewEngineState;
  declare const store: SessionStore;
  declare const analysis: AnalysisSession;
  store.patch('mine', { filterValid: false });
  store.patch('mine', { kind: 'record', page: 2, instance: session.instance, baseline: session.baseline, result: session.result });
  store.patch('analysis', { kind: 'analysis', instance: analysis.instance, result: analysis.result });
  // @ts-expect-error kind-specific updates require a discriminator
  store.patch('mine', { page: 2 });
  // @ts-expect-error analysis sessions cannot paginate
  store.patch('analysis', { kind: 'analysis', page: 2 });
  // @ts-expect-error record sessions cannot carry aggregate query plans
  store.patch('mine', { kind: 'record', pendingQuery: analysis.pendingQuery });
  // @ts-expect-error result must match the session kind
  store.patch('analysis', { kind: 'analysis', result: session.result });
  // @ts-expect-error instance configuration must match the session kind
  store.patch('mine', { kind: 'record', instance: analysis.instance });
  const mixed = { filterValid: false, page: 2 };
  // @ts-expect-error variable patches also require kind for non-common fields
  store.patch('mine', mixed);
  const crossed = { kind: 'analysis' as const, page: 2 };
  // @ts-expect-error variable patches cannot smuggle fields of the other kind
  store.patch('analysis', crossed);
  // @ts-expect-error record updates cannot accept an analysis configuration
  store.updateInstance(session, analysis.instance);
  // @ts-expect-error baseline must match the session kind
  store.patch('analysis', { kind: 'analysis', baseline: session.baseline });

  const snapshot = engine.getSnapshot();
  // @ts-expect-error core instance metadata is a snapshot
  snapshot.sessions.mine.instance.title = 'changed';
  // @ts-expect-error core query configuration is a snapshot
  snapshot.sessions.mine.instance.config.sort.pop();
  // @ts-expect-error core drafts are snapshots
  session.filterDraft.root.field = 'other';
  // @ts-expect-error definition metadata is a snapshot
  state.definition!.title = 'changed';
  const mine = snapshot.sessions.mine;
  if(mine.kind === 'record') {
    const commands=engine.record(mine.instance.id);
    commands.setFilterDraft(mine.filterDraft);
    commands.setSort(mine.instance.config.sort);
    const presentation=mine.instance.config.presentation;
    if(presentation.layout==='table')commands.setColumns(presentation.table.columns);
    if(presentation.layout==='card')commands.setCardConfig(presentation.card);
    commands.setLayout('card');commands.applyFilter();
    // @ts-expect-error queries compile canonical configuration, never accept expressions
    commands.applyFilter(mine.appliedFilter);
  }
  // @ts-expect-error record operations require an instance-bound command
  engine.setPage(2);
  // @ts-expect-error analysis cannot paginate records
  engine.analysis('analysis').setPage(2);
  const revision:string=session.baseline.revision;
  const savedCopy:Promise<string|undefined>=engine.saveAs({title:'Copy',scope:{type:'personal'}},'mine');
  // @ts-expect-error extension records are snapshots
  cell.record.amount = 100;
  // @ts-expect-error instance metadata is a snapshot
  cell.instance.title = 'changed';
  // @ts-expect-error column preferences are snapshots
  cell.column.width = 320;
  if (editor.field?.options?.[0]) {
    // @ts-expect-error nested field metadata is a snapshot
    editor.field.options[0].label = 'changed';
  }
`;
const config = ts.readConfigFile(configPath, ts.sys.readFile);
const parsed = ts.parseJsonConfigFileContent(
  config.config,
  ts.sys,
  dirname(configPath),
);
const options = {
  ...parsed.options,
  noEmit: true,
  incremental: false,
  composite: false,
};
const host = ts.createCompilerHost(options);
const read = host.readFile.bind(host),
  exists = host.fileExists.bind(host);
host.readFile = name => (name === file ? source : read(name));
host.fileExists = name => name === file || exists(name);
const program = ts.createProgram([file], options, host);
assert.deepEqual(
  ts
    .getPreEmitDiagnostics(program)
    .map(diagnostic =>
      ts.flattenDiagnosticMessageText(diagnostic.messageText, ' '),
    ),
  [],
);
