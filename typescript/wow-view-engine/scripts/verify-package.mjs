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

// Run after building: node packages/view-engine/scripts/verify-package.mjs
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  cpSync,
  existsSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  realpathSync,
  rmSync,
  statSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

const root = fileURLToPath(new URL('../', import.meta.url));
const readJson = file => JSON.parse(readFileSync(file, 'utf8'));
const manifest = readJson(join(root, 'package.json'));
const temporary = mkdtempSync(join(realpathSync(tmpdir()), 'fve-package-'));
const run = (command, args, cwd) =>
  execFileSync(command, args, {
    cwd,
    encoding: 'utf8',
    timeout: 60_000,
    maxBuffer: 4 * 1024 * 1024,
  });
function filesIn(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const file = join(directory, entry.name);
    return entry.isDirectory() ? filesIn(file) : [file];
  });
}
function verifyCoreImports(entry, packageDirectory) {
  const visited = new Set();
  function visit(file) {
    if (visited.has(file)) return;
    visited.add(file);
    const ast = ts.createSourceFile(
      file,
      readFileSync(file, 'utf8'),
      ts.ScriptTarget.Latest,
      true,
      ts.ScriptKind.JS,
    );
    function inspect(node) {
      let specifier;
      if (
        (ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) &&
        node.moduleSpecifier
      )
        specifier = node.moduleSpecifier.text;
      if (
        ts.isCallExpression(node) &&
        node.expression.kind === ts.SyntaxKind.ImportKeyword
      ) {
        assert.ok(
          ts.isStringLiteral(node.arguments[0]),
          'Built core has a computed import',
        );
        specifier = node.arguments[0].text;
      }
      if (specifier?.startsWith('.')) {
        const target = resolve(dirname(file), specifier);
        assert.ok(
          target.startsWith(join(packageDirectory, 'dist') + '/'),
          'Core import escapes dist',
        );
        assert.ok(target.endsWith('.js'), `Core runtime imports ${specifier}`);
        visit(target);
      } else if (specifier) {
        assert.doesNotMatch(
          specifier,
          /^(react(?:$|[-/])|@ahoo-wang\/fetcher-react(?:$|\/)|@tanstack\/|@base-ui\/|lucide-react$)/,
          'Core imports a UI dependency',
        );
      }
      ts.forEachChild(node, inspect);
    }
    inspect(ast);
  }
  visit(entry);
  return visited.size;
}
function verifyTypes(directory) {
  const exampleDirectory = join(root, 'examples/react');
  assert.ok(
    existsSync(join(exampleDirectory, 'sales-order/OrderWorkbench.tsx')),
    'React example is required for package verification',
  );
  cpSync(exampleDirectory, join(directory, 'examples/react'), {
    recursive: true,
  });
  const probe = join(directory, 'public-types.tsx');
  writeFileSync(
    probe,
    `
    import { ViewEngine, compileBuiltinFilter, clearBuiltinFilterProps, type DeepReadonly, type ViewHost, type ViewInstance, type RecordViewInstance, type RecordQuerySource } from '${manifest.name}';
    import { FilterPanel, ViewPage, useViewEngine, type CellRendererProps, type FilterEditorProps, type FilterExtensions } from '${manifest.name}/react';
    import { OrderWorkbench } from './examples/react/sales-order/OrderWorkbench.js';
    import type { FilterOptionSource, FilterFieldDefinition, ViewDefinition } from '${manifest.name}';
    import { FilterRemoteSelect, FilterMultiSelect, FilterDateTimeRange } from '${manifest.name}/react';
    import { TextCell, TagsCell, StatusCell, LinkCell, DateTimeCell, NumberCell } from '${manifest.name}/react';
    export const standaloneCells = <><TextCell value="001" ellipsis copyable/><TagsCell value={[1,'1',false]}/><StatusCell value={1} tones={[{value:1,tone:'success'}]}/><LinkCell value="详情" href="/orders/1"/><DateTimeCell value={0} timeZone="UTC"/><NumberCell value={0.125} format={{style:'percent'}}/></>;
    // @ts-expect-error Numeric cells do not parse currency strings.
    export const invalidNumber = <NumberCell value="¥1,234"/>;
    declare const optionSource: FilterOptionSource;
    export const multi = <FilterRemoteSelect label="users" source={optionSource} multiple values={[1,'1']} onValueChange={values => { const ids: (string|number)[] = values; void ids; }} />;
    export const single = <FilterRemoteSelect label="user" source={optionSource} value={1} onValueChange={value => { const id: string|number|null = value; void id; }} />;
    export const localMulti = <FilterMultiSelect label="ids" options={[{value: 1,label:'one'}]} value={[1]} onValueChange={values => { const id: number = values[0]; void id; }} />;
    export const datedDefinition: ViewDefinition = { id: 'dates', sourceId: 'dates', title: 'Dates', record: { allowedLayouts: ['table'], rowKey: 'id' }, timeZone: 'Asia/Shanghai', fields: [{field: 'created',label:'Created',type:'datetime',editor:{name:'builtin',options:{showTime:true}}}] };
    export const dateRange = <FilterDateTimeRange field={datedDefinition.fields[0]} timeZone={datedDefinition.timeZone} showTime value={{lowerBound:{date:'2026-09-06',time:'09:00'},upperBound:{date:'2026-09-06',time:'10:00'}}} onValueChange={() => {}} />;
    // @ts-expect-error Fields use the view or panel timezone instead of individual overrides.
    export const fieldTimeZone: FilterFieldDefinition = {field:'created',label:'Created',type:'datetime',timeZone:'UTC'};
    // @ts-expect-error A multiple remote picker cannot accept scalar value.
    export const wrongSelection = <FilterRemoteSelect label="users" source={optionSource} multiple value={1} onValueChange={() => {}} />;
    declare const paged: NonNullable<RecordQuerySource['paged']>;
    declare const cursor: NonNullable<RecordQuerySource['cursor']>;
    export const pagedOnly: RecordQuerySource = {paged};
    export const cursorOnly: RecordQuerySource = {cursor};
    // @ts-expect-error A record source must provide at least one query mode.
    export const noQuery: RecordQuerySource = {};
    declare const engine: ViewEngine;
    declare const host: ViewHost;
    declare const instance: DeepReadonly<RecordViewInstance>;
    const record = engine.record(instance.id);
    if (instance.config.presentation.layout === 'table') {
      record.setColumns(instance.config.presentation.table.columns);
    } else {
      record.setCardConfig(instance.config.presentation.card);
    }
    void record.setSort(instance.config.sort);
    record.setFilterDraft(instance.config.filters);
    void record.applyFilter();
    // @ts-expect-error Query expressions cannot replace canonical editing state.
    void record.applyFilter({ op: 'MATCH_ALL' });
    export const controlledPanel = <FilterPanel fields={[]} value={instance.config.filters} onChange={configuration => record.setFilterDraft(configuration)} appliedValue={instance.config.filters} onApply={({configuration, expression}) => { record.setFilterDraft(configuration); void expression; }} />;
    export const localPanel = <FilterPanel fields={[]} defaultValue={instance.config.filters} onApply={({expression}) => { void expression; }} />;
    // @ts-expect-error Controlled and local ownership are mutually exclusive.
    export const mixedPanel = <FilterPanel fields={[]} value={instance.config.filters} defaultValue={instance.config.filters} onChange={() => {}} onApply={() => {}} />;
    const filterEditor = ({ props, onChange }: FilterEditorProps) => {
      // @ts-expect-error Persisted component props are readonly snapshots.
      props.value = 'mutated';
      return <button onClick={() => onChange({ ...props, displayLabel: 'Custom label' })}>Rename</button>;
    };
    export const filters: FilterExtensions = { filters: { custom: {
      component: filterEditor, modes: ['simple'], compile: compileBuiltinFilter, clear: clearBuiltinFilterProps,
    } } };
    // @ts-expect-error Public snapshots must remain readonly.
    engine.getSnapshot().instanceIds.push('mutated');
    const cell = ({ record }: CellRendererProps) => {
      // @ts-expect-error Custom renderers must not mutate source records.
      record.id = 'mutated';
      return String(record.id);
    };
    export function Page() { const binding = useViewEngine({ definitionId: 'orders', scopeKey: 'user:tenant', host, extensions: { ...filters, cells: { custom: cell } } }); return <ViewPage {...binding} />; }
    // @ts-expect-error React filter definitions are registered only through extensions.filters.
    export const splitRegistration = useViewEngine({ definitionId: 'orders', scopeKey: 'user:tenant', host, filterCompilers: { custom: { compile: compileBuiltinFilter } } });
    export const example = OrderWorkbench;
  `,
  );
  const reactTypes = join(root, 'node_modules/@types/react');
  const options = {
    noEmit: true,
    strict: true,
    skipLibCheck: true,
    esModuleInterop: true,
    target: ts.ScriptTarget.ES2020,
    module: ts.ModuleKind.ESNext,
    moduleResolution: ts.ModuleResolutionKind.Bundler,
    jsx: ts.JsxEmit.ReactJSX,
    lib: ['lib.es2020.d.ts', 'lib.dom.d.ts', 'lib.dom.iterable.d.ts'],
    paths: {
      react: [join(reactTypes, 'index.d.ts')],
      'react/jsx-runtime': [join(reactTypes, 'jsx-runtime.d.ts')],
      'react/jsx-dev-runtime': [join(reactTypes, 'jsx-dev-runtime.d.ts')],
    },
  };
  const coreProbe = join(directory, 'headless-types.ts');
  writeFileSync(
    coreProbe,
    `import { ViewEngine, type MemoryViewHostOptions } from '${manifest.name}';
     export const engine = ViewEngine;
     export const store: NonNullable<MemoryViewHostOptions['store']> = new Map();`,
  );
  const headless = ts.createProgram([coreProbe], {
    ...options,
    module: ts.ModuleKind.NodeNext,
    moduleResolution: ts.ModuleResolutionKind.NodeNext,
    skipLibCheck: false,
    lib: ['lib.es2022.d.ts'],
    types: ['node'],
    typeRoots: [join(root, 'node_modules/@types')],
    paths: {},
  });
  const headlessDiagnostics = ts.getPreEmitDiagnostics(headless);
  assert.equal(
    headlessDiagnostics.length,
    0,
    ts.formatDiagnosticsWithColorAndContext(headlessDiagnostics, {
      getCanonicalFileName: file => file,
      getCurrentDirectory: () => directory,
      getNewLine: () => '\n',
    }),
  );
  assert.ok(
    !headless
      .getSourceFiles()
      .some(file => /lib\.dom(?:\.|\b)/.test(file.fileName)),
    'Headless types loaded DOM declarations',
  );
  // Map only installed React declarations; both library imports must resolve through the packed exports.
  for (const [specifier, target] of [
    [manifest.name, manifest.exports['.'].types],
    [manifest.name + '/react', manifest.exports['./react'].types],
  ]) {
    const resolved = ts.resolveModuleName(
      specifier,
      probe,
      options,
      ts.sys,
    ).resolvedModule;
    assert.equal(resolved?.resolvedFileName, resolve(directory, target));
  }
  const nodeProbe = join(directory, 'nodenext-consumer.mts');
  writeFileSync(
    nodeProbe,
    `
    import { compileAnalysis, type AnalysisViewConfig, type AnalysisCompileContext } from '${manifest.name}';
    import { useViewEngine, ViewPage, type UseViewEngineOptions } from '${manifest.name}/react';
    declare const config: AnalysisViewConfig;
    declare const context: AnalysisCompileContext;
    declare const options: UseViewEngineOptions;
    const result = compileAnalysis(config, context);
    const binding = useViewEngine(options);
    type Query = NonNullable<typeof result.plan>['query'];
    // @ts-expect-error Missing metrics must be rejected even through the public package boundary.
    const invalid: Query = { metrics: [] };
    // @ts-expect-error Scope is required for lifecycle isolation.
    useViewEngine({ host: options.host, definitionId: options.definitionId });
    void [binding, ViewPage, invalid];
  `,
  );
  const nodeProgram = ts.createProgram([nodeProbe], {
    ...options,
    module: ts.ModuleKind.NodeNext,
    moduleResolution: ts.ModuleResolutionKind.NodeNext,
    skipLibCheck: false,
  });
  const nodeDiagnostics = ts.getPreEmitDiagnostics(nodeProgram);
  assert.equal(
    nodeDiagnostics.length,
    0,
    ts.formatDiagnosticsWithColorAndContext(nodeDiagnostics, {
      getCanonicalFileName: file => file,
      getCurrentDirectory: () => directory,
      getNewLine: () => '\n',
    }),
  );
  const examples = filesIn(join(directory, 'examples/react')).filter(file =>
    /\.tsx?$/.test(file),
  );
  const program = ts.createProgram(
    [probe, ...examples, join(root, 'node_modules/vite/client.d.ts')],
    options,
  );
  const diagnostics = ts.getPreEmitDiagnostics(program);
  if (diagnostics.length)
    throw new Error(
      ts.formatDiagnosticsWithColorAndContext(diagnostics, {
        getCanonicalFileName: file => file,
        getCurrentDirectory: () => directory,
        getNewLine: () => '\n',
      }),
    );
  assert.ok(
    !program
      .getSourceFiles()
      .some(file => file.fileName.startsWith(join(root, 'src') + '/')),
    'Type integration reached private source',
  );
  console.log(
    `Public types passed: strict NodeNext contracts, core readonly inputs, readonly snapshots/renderers and ${examples.length} React example modules.`,
  );
}

try {
  const targets = Object.values(manifest.exports).flatMap(value =>
    typeof value === 'string' ? value : Object.values(value),
  );
  for (const target of targets) {
    assert.ok(
      target.startsWith('./dist/'),
      `Public entry points outside dist: ${target}`,
    );
    assert.ok(
      statSync(resolve(root, target)).size > 0,
      `Empty public entry: ${target}`,
    );
  }
  assert.equal(manifest.module, manifest.exports['.'].import);
  assert.equal(manifest.types, manifest.exports['.'].types);
  const css = readFileSync(
    resolve(root, manifest.exports['./styles.css']),
    'utf8',
  );
  assert.match(css, /\.fve-root\b/);
  assert.ok(css.includes('--fve-background'), 'Missing scoped theme variables');
  assert.ok(
    !css.includes('--tw-'),
    'Unscoped Tailwind variables leaked into CSS',
  );
  run('pnpm', ['pack', '--pack-destination', temporary], root);
  const archives = readdirSync(temporary).filter(file => file.endsWith('.tgz'));
  assert.equal(archives.length, 1);
  const archive = join(temporary, archives[0]);
  const contents = run('tar', ['-tzf', archive], root).trim().split('\n');
  for (const entry of contents) {
    assert.ok(
      entry.startsWith('package/') && !entry.split('/').includes('..'),
      `Unexpected archive path: ${entry}`,
    );
    assert.doesNotMatch(
      entry,
      /^package\/(src|test|node_modules)\//,
      'Private development files entered the package',
    );
  }
  run('tar', ['-xzf', archive, '-C', temporary], root);
  const packed = join(temporary, 'package');
  const packedManifest = readJson(join(packed, 'package.json'));
  assert.equal(packedManifest.name, manifest.name);
  assert.equal(packedManifest.version, manifest.version);
  assert.deepEqual(packedManifest.exports, manifest.exports);
  for (const dependencies of [
    packedManifest.dependencies,
    packedManifest.peerDependencies,
  ])
    for (const version of Object.values(dependencies ?? {}))
      assert.doesNotMatch(version, /^(catalog|workspace):/);
  const distribution = filesIn(join(root, 'dist'));
  assert.ok(
    !distribution.some(file => /(?:^|\/)(?:http\/|HttpView)/.test(file)),
    'Development HTTP files leaked into dist',
  );
  for (const file of distribution) {
    const path = relative(root, file);
    assert.ok(
      contents.includes('package/' + path),
      `Missing packed file: ${path}`,
    );
    assert.deepEqual(
      readFileSync(join(packed, path)),
      readFileSync(file),
      `Packed file differs: ${path}`,
    );
  }
  for (const file of ['README.md', 'README.zh-CN.md', 'THIRD_PARTY_NOTICES.md'])
    assert.ok(
      contents.includes('package/' + file),
      `Missing package document: ${file}`,
    );
  assert.match(
    readFileSync(resolve(packed, manifest.exports['./react'].import), 'utf8'),
    /from ["']react\/compiler-runtime["']/,
    'React entry was built without React Compiler',
  );
  const coreModules = verifyCoreImports(
    resolve(packed, manifest.exports['.'].import),
    packed,
  );
  // Reuse installed dependencies without an install; package self-references still resolve to this extracted package.
  symlinkSync(join(root, 'node_modules'), join(packed, 'node_modules'), 'dir');
  const runtimeProbe = `
    import assert from 'node:assert/strict';
    import { fileURLToPath } from 'node:url';
    const core = await import(${JSON.stringify(manifest.name)});
    const react = await import(${JSON.stringify(manifest.name + '/react')});
    assert.equal(fileURLToPath(import.meta.resolve(${JSON.stringify(manifest.name)})), ${JSON.stringify(resolve(packed, manifest.exports['.'].import))});
    assert.equal(fileURLToPath(import.meta.resolve(${JSON.stringify(manifest.name + '/react')})), ${JSON.stringify(resolve(packed, manifest.exports['./react'].import))});
    assert.equal(typeof core.ViewEngine, 'function');
    assert.deepEqual(Object.keys(core).filter(name => name.startsWith('HttpView') || name === 'VIEW_SERVICE_STATUS'), [], 'Experimental HTTP API leaked into the package');
    assert.equal(typeof react.ViewPage, 'function');
    for (const name of ['FilterMultiSelect','FilterRemoteSelect','FilterTextValues','FilterDateTimeRange','TextCell','TagsCell','StatusCell','LinkCell','DateTimeCell','NumberCell']) assert.equal(typeof react[name], 'function', name);
    const configured = core.createFilterConfiguration({id:'selected',operator:'IN',field:'id',component:{name:'multi-select'},props:{values:[1],selectedOptions:[{value:1,label:'One'}]}});
    assert.deepEqual(core.compileFilterConfiguration(configured,[{field:'id',label:'ID',type:'number'}]).expression,{op:'IN',field:'id',values:[1]});
    const dates = core.createFilterConfiguration({id:'date',operator:'EQ',field:'created',component:{name:'builtin'},props:{value:{date:'2026-09-06'}}});
    assert.deepEqual(core.compileFilterConfiguration(dates,[{field:'created',label:'Created',type:'datetime'}],undefined,undefined,'Asia/Shanghai').expression,{op:'BETWEEN',field:'created',lowerBound:Date.parse('2026-09-05T16:00:00Z'),upperBound:Date.parse('2026-09-06T15:59:59.999Z')});
  `;
  run(process.execPath, ['--input-type=module', '-e', runtimeProbe], packed);
  cpSync(join(root, 'examples/core.mjs'), join(packed, 'core-example.mjs'));
  process.stdout.write(run(process.execPath, ['core-example.mjs'], packed));
  verifyTypes(packed);
  const checksum = createHash('sha256')
    .update(readFileSync(archive))
    .digest('hex');
  console.log(
    `${manifest.name}@${manifest.version}: ${targets.length} public targets, scoped CSS, ${distribution.length} identical packed dist files and ${coreModules} core runtime modules verified.`,
  );
  console.log(`Archive SHA-256: ${checksum}`);
} finally {
  rmSync(temporary, { recursive: true, force: true });
}
