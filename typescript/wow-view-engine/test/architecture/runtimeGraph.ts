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

import { readFileSync, existsSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import { expect } from 'vitest';

export const root = fileURLToPath(new URL('../../src/', import.meta.url));
const options: ts.CompilerOptions = {
  target: ts.ScriptTarget.ES2022,
  module: ts.ModuleKind.ESNext,
  jsx: ts.JsxEmit.ReactJSX,
};
export function runtimeModule(fileName: string, source: string) {
  const javascript = ts.transpileModule(source, {
    fileName,
    compilerOptions: options,
  }).outputText;
  const ast = ts.createSourceFile(
    fileName,
    javascript,
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.JS,
  );
  const imports: string[] = [];
  function visit(node: ts.Node) {
    if (
      (ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) &&
      node.moduleSpecifier
    ) {
      if (!ts.isStringLiteral(node.moduleSpecifier))
        throw new Error('Expected a static module specifier');
      imports.push(node.moduleSpecifier.text);
    }
    if (
      ts.isCallExpression(node) &&
      node.expression.kind === ts.SyntaxKind.ImportKeyword
    ) {
      const target = node.arguments[0];
      if (!target || !ts.isStringLiteral(target))
        throw new Error(`${fileName}: computed imports cannot be verified`);
      imports.push(target.text);
    }
    ts.forEachChild(node, visit);
  }
  visit(ast);
  return { javascript, imports };
}
interface Module {
  javascript: string;
  local: string[];
  external: string[];
  assets: string[];
}
export function graphFrom(entries: string[]) {
  const graph = new Map<string, Module>();
  function walk(file: string) {
    if (graph.has(file)) return;
    const runtime = runtimeModule(file, readFileSync(file, 'utf8'));
    const module: Module = {
      javascript: runtime.javascript,
      local: [],
      external: [],
      assets: [],
    };
    graph.set(file, module);
    for (const specifier of runtime.imports) {
      if (!specifier.startsWith('.') && !specifier.startsWith('@/')) {
        module.external.push(specifier);
        continue;
      }
      const requested = specifier.startsWith('@/')
        ? resolve(root, specifier.slice(2))
        : resolve(dirname(file), specifier);
      if (requested.endsWith('.css')) {
        expect(existsSync(requested), requested).toBe(true);
        module.assets.push(requested);
        continue;
      }
      const target = [
        requested.replace(/\.js$/, '.ts'),
        requested.replace(/\.js$/, '.tsx'),
        requested + '.ts',
        requested + '.tsx',
        requested,
      ].find(candidate => existsSync(candidate));
      if (!target)
        throw new Error(`${relative(root, file)} cannot resolve ${specifier}`);
      module.local.push(target);
      walk(target);
    }
  }
  entries.forEach(walk);
  return graph;
}
export function cyclesIn(graph: Map<string, Module>) {
  const complete = new Set<string>();
  const stack: string[] = [];
  const cycles: string[][] = [];
  function visit(file: string) {
    const index = stack.indexOf(file);
    if (index >= 0) {
      cycles.push(
        [...stack.slice(index), file].map(file => relative(root, file)),
      );
      return;
    }
    if (complete.has(file)) return;
    stack.push(file);
    graph.get(file)?.local.forEach(visit);
    stack.pop();
    complete.add(file);
  }
  graph.forEach((_, file) => visit(file));
  return cycles;
}
// Standard Node Web APIs do not require a DOM. Other runtime globals from lib.dom are browser dependencies.
const portableGlobals = new Set([
  'AbortController',
  'AbortSignal',
  'DOMException',
  'crypto',
  'structuredClone',
  'console',
  'setTimeout',
  'clearTimeout',
  'setInterval',
  'clearInterval',
  'queueMicrotask',
  'URL',
  'URLSearchParams',
  'TextEncoder',
  'TextDecoder',
  'fetch',
  'Request',
  'Response',
  'Headers',
  'performance',
  'ReadableStream',
  'WritableStream',
  'TransformStream',
  'Blob',
  'FormData',
  'Event',
  'EventTarget',
  'atob',
  'btoa',
]);

const domLibrary = ts.createSourceFile(
  'lib.dom.d.ts',
  readFileSync(
    join(dirname(ts.getDefaultLibFilePath(options)), 'lib.dom.d.ts'),
    'utf8',
  ),
  ts.ScriptTarget.Latest,
  true,
);
const browserNames = new Set(
  domLibrary.statements
    .flatMap(statement =>
      ts.isVariableStatement(statement)
        ? statement.declarationList.declarations.map(declaration =>
            declaration.name.getText(domLibrary),
          )
        : ts.isFunctionDeclaration(statement) && statement.name
          ? [statement.name.text]
          : [],
    )
    .filter(name => !portableGlobals.has(name)),
);
const declarationFile = join(root, 'dom-globals.d.ts');
const declarations = [...browserNames]
  .map(name => `declare var ${name}: any;`)
  .join('\n');

export function browserGlobalsIn(graph: Map<string, Module>) {
  // Only bind runtime modules and browser global names; no libraries, @types or imported packages are loaded.
  const files = new Map(
    [...graph].map(([file, module]) => [
      file.replace(/\.tsx?$/, '.js'),
      module.javascript,
    ]),
  );
  const runtimeFiles = [...files.keys()];
  files.set(declarationFile, declarations);
  const compilerOptions: ts.CompilerOptions = {
    ...options,
    allowJs: true,
    noEmit: true,
    noLib: true,
    noResolve: true,
    types: [],
  };
  const host = ts.createCompilerHost(compilerOptions);
  host.readFile = file => files.get(file);
  host.fileExists = file => files.has(file);
  host.getSourceFile = file =>
    files.has(file)
      ? ts.createSourceFile(
          file,
          files.get(file)!,
          ts.ScriptTarget.Latest,
          true,
          file === declarationFile ? ts.ScriptKind.TS : ts.ScriptKind.JS,
        )
      : undefined;
  const program = ts.createProgram([...files.keys()], compilerOptions, host);
  const checker = program.getTypeChecker();
  const references: string[] = [];
  for (const file of runtimeFiles) {
    const source = program.getSourceFile(file)!;
    function visit(node: ts.Node) {
      if (
        (ts.isIdentifier(node) ||
          (ts.isStringLiteral(node) &&
            ts.isElementAccessExpression(node.parent))) &&
        browserNames.has(node.text)
      ) {
        const symbol = checker.getSymbolAtLocation(node);
        if (
          symbol?.declarations?.some(
            declaration =>
              declaration.getSourceFile().fileName === declarationFile,
          )
        )
          references.push(`${relative(root, file)}: ${node.text}`);
      }
      ts.forEachChild(node, visit);
    }
    visit(source);
  }
  return references;
}
