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

import { dirname, resolve } from 'node:path';
import ts from 'typescript';
import { expect, it } from 'vitest';

it('checks readonly snapshots and component registration boundaries at compile time', () => {
  const configPath = resolve('tsconfig.json');
  const file = resolve('src/__extension_contract__.ts');
  const source = `
    import type { CellRendererProps } from './record/recordReactTypes.js';
    import type { FilterEditorProps } from './filter/filterReactTypes.js';
    import type { DeepReadonly } from './lib/types.js';
    import type { ViewEngine } from './record/ViewEngine.js';
    import type { ViewPageProps } from './record/ViewPage.js';
    import type { FilterRegistration } from './filter/filterReactTypes.js';
    import type { ViewEngineOptions } from './record/recordModel.js';
    import type { RecordSession, ViewEngineState } from './record/recordModel.js';
    declare const page: ViewPageProps;
    declare const registration: FilterRegistration;
    const extensions: ViewPageProps['extensions'] = { filters: { custom: registration } };
    const headless: ViewEngineOptions['filterCompilers'] = { custom: { compile: registration.compile } };
    // @ts-expect-error React registers compilation with its component, never through a second registry
    page.filterCompilers;
    declare const cell: CellRendererProps;
    declare const editor: FilterEditorProps;
    const value: unknown = cell.record.amount;
    const callback: DeepReadonly<(id: string) => boolean> = id => id.length > 0;
    callback('record-id');
    declare const engine: ViewEngine;
    declare const session: RecordSession;
    declare const state: ViewEngineState;
    const snapshot = engine.getSnapshot();
    // @ts-expect-error core instance metadata is a snapshot
    snapshot.sessions.mine.instance.title = 'changed';
    // @ts-expect-error core query configuration is a snapshot
    snapshot.sessions.mine.instance.config.sort.pop();
    // @ts-expect-error core drafts are snapshots
    session.filterDraft.root.field = 'other';
    // @ts-expect-error definition metadata is a snapshot
    state.definition!.title = 'changed';
    engine.setFilterDraft(snapshot.sessions.mine.filterDraft);
    engine.setSort(snapshot.sessions.mine.instance.config.sort);
    engine.setColumns(snapshot.sessions.mine.instance.config.presentation.table.columns);
    engine.applyFilter('mine');
    // @ts-expect-error queries compile the canonical configuration rather than accepting expressions
    engine.applyFilter(snapshot.sessions.mine.appliedFilter);
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
  expect(
    ts
      .getPreEmitDiagnostics(program)
      .map(diagnostic =>
        ts.flattenDiagnosticMessageText(diagnostic.messageText, ' '),
      ),
  ).toEqual([]);
}, 20_000);
