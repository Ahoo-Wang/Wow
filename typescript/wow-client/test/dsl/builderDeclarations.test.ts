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
 * The builder objects of the DSL, `filter` and `aggregation`, as a consumer
 * sees them: each member's documentation and signature in the declarations
 * the package emits, and the members the object holds at run time.
 *
 * The declarations are emitted here, in memory, from `src/dsl.ts`, and read
 * back as a consumer's compiler reads them; each member's doc comment, its
 * tags and its type are compared with `golden/<object>-declarations.json`.
 * However the objects are put together, a member's documentation or
 * signature that the declarations lose or change fails here. A change made on
 * purpose is recorded with `pnpm exec vitest run
 * test/dsl/builderDeclarations.test.ts -u`.
 *
 * Other declarations' docs link into the objects (`{@link filter.search}`),
 * and an editor shows such a link as a link only where TypeScript resolves
 * it. Every one is resolved here as an editor resolves it; those that do not
 * resolve today are listed in `golden/unresolved-builder-links.json` by file
 * and link, with how many times the file holds it, so a link that stops
 * resolving adds an entry or raises a count and fails by name, while an edit
 * elsewhere in the file changes nothing.
 */

import { dirname, join, resolve } from 'node:path';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';
import { aggregation, filter } from '../../src/dsl.js';

const PACKAGE = resolve(__dirname, '../..');
const ENTRY = join(PACKAGE, 'src', 'dsl.ts');
const OUT = join(PACKAGE, 'emitted-declarations');

/** The package's own compiler options, emitting declarations only. */
function compilerOptions(): ts.CompilerOptions {
  const path = join(PACKAGE, 'tsconfig.json');
  const { config } = ts.readConfigFile(path, ts.sys.readFile);
  const { options } = ts.parseJsonConfigFileContent(
    config,
    ts.sys,
    dirname(path),
  );
  return {
    ...options,
    declaration: true,
    emitDeclarationOnly: true,
    noEmit: false,
    composite: false,
    incremental: false,
    declarationMap: false,
    outDir: OUT,
  };
}

/**
 * The declarations `src/dsl.ts` emits, as a program over them alone: what a
 * consumer of the package compiles against.
 */
function emittedProgram(): { program: ts.Program; entry: string } {
  const options = compilerOptions();
  const emitted = new Map<string, string>();
  const result = ts
    .createProgram([ENTRY], options)
    .emit(undefined, (name, text) => emitted.set(resolve(name), text));
  expect(result.emitSkipped).toBe(false);
  const host = ts.createCompilerHost(options);
  const fileExists = host.fileExists.bind(host);
  const readFile = host.readFile.bind(host);
  const directoryExists = host.directoryExists?.bind(host);
  host.fileExists = name => emitted.has(resolve(name)) || fileExists(name);
  host.readFile = name => emitted.get(resolve(name)) ?? readFile(name);
  host.directoryExists = name =>
    [...emitted.keys()].some(file => file.startsWith(`${resolve(name)}/`)) ||
    (directoryExists?.(name) ?? false);
  const entry = join(OUT, 'dsl.d.ts');
  return {
    program: ts.createProgram(
      [entry],
      { ...options, noEmit: true, declaration: false },
      host,
    ),
    entry,
  };
}

/**
 * A doc comment's text, its links written out: `{@link target | text}`, and
 * `(unresolved)` after a target a consumer's compiler does not find, which an
 * editor shows as plain text rather than as a link.
 */
function text(parts: readonly ts.SymbolDisplayPart[] | undefined): string {
  let out = '';
  let link: { name?: string; text?: string } | undefined;
  for (const part of parts ?? []) {
    if (part.kind === 'link' && part.text.startsWith('{@')) {
      link = {};
    } else if (link && part.kind === 'linkName') {
      link.name = part.text;
    } else if (link && part.kind === 'linkText') {
      link.text = part.text.trim();
    } else if (link && part.kind === 'link') {
      out += link.name
        ? `{@link ${link.name}${link.text ? ` | ${link.text}` : ''}}`
        : `{@link ${link.text} (unresolved)}`;
      link = undefined;
    } else {
      out += part.text;
    }
  }
  return out;
}

/** Each member of an exported object: its doc comment, its tags and its type, by name. */
function membersOf(
  program: ts.Program,
  entry: string,
  name: string,
): Record<string, { doc: string; tags: string[]; type: string }> {
  const checker = program.getTypeChecker();
  const module = checker.getSymbolAtLocation(program.getSourceFile(entry)!)!;
  const exported = checker
    .getExportsOfModule(module)
    .find(symbol => symbol.name === name)!;
  const declaration = exported.valueDeclaration ?? exported.declarations![0];
  const type = checker.getTypeOfSymbolAtLocation(exported, declaration);
  const members = checker
    .getPropertiesOfType(type)
    .map(member => [
      member.name,
      {
        doc: text(member.getDocumentationComment(checker)),
        tags: member
          .getJsDocTags(checker)
          .map(tag => `@${tag.name} ${text(tag.text)}`.trimEnd()),
        type: checker.typeToString(
          checker.getTypeOfSymbolAtLocation(member, declaration),
          undefined,
          ts.TypeFormatFlags.NoTruncation,
        ),
      },
    ])
    .sort(([left], [right]) => String(left).localeCompare(String(right)));
  return Object.fromEntries(members);
}

/**
 * Every link in the docs of the emitted declarations that points at a member
 * of the builder object `name`, by its target or by the text it shows (a
 * link to a member through its group shows the member's name): the
 * declaration file it is written in, and whether TypeScript resolves its
 * target to a declaration, as an editor does when it shows the doc.
 */
function linksInto(
  program: ts.Program,
  name: string,
): { file: string; link: string; resolved: boolean }[] {
  const checker = program.getTypeChecker();
  const links: { file: string; link: string; resolved: boolean }[] = [];
  for (const file of program.getSourceFiles()) {
    if (!file.fileName.startsWith(OUT)) continue;
    const visit = (node: ts.Node): void => {
      if (ts.isJSDocLink(node) && node.name) {
        const target = node.name.getText(file);
        const shown = node.text
          .replace(/^\s*\|?\s*/, '')
          .replace(/`/g, '')
          .trim();
        if (target.startsWith(`${name}.`) || shown.startsWith(`${name}.`)) {
          links.push({
            file: file.fileName.slice(OUT.length + 1),
            link: shown ? `${target} | ${shown}` : target,
            resolved:
              (checker.getSymbolAtLocation(node.name)?.declarations?.length ??
                0) > 0,
          });
        }
      }
      ts.forEachChild(node, visit);
      for (const doc of (node as { jsDoc?: ts.JSDoc[] }).jsDoc ?? []) {
        visit(doc);
      }
    };
    visit(file);
  }
  return links;
}

describe.each([
  ['filter', filter],
  ['aggregation', aggregation],
] as const)('the `%s` builders', (name, builders) => {
  it('keep every member documented, with its signature, in the emitted declarations', async () => {
    const { program, entry } = emittedProgram();
    const members = membersOf(program, entry, name);
    expect(Object.keys(members).length).toBeGreaterThan(20);
    for (const [member, { doc }] of Object.entries(members)) {
      expect(doc, `${name}.${member} lost its doc comment`).not.toBe('');
    }
    await expect(JSON.stringify(members, null, 2) + '\n').toMatchFileSnapshot(
      `../golden/${name}-declarations.json`,
    );
  }, 60_000);

  it('hold at run time the members the declarations list', () => {
    const { program, entry } = emittedProgram();
    expect(Object.keys(builders).sort()).toEqual(
      Object.keys(membersOf(program, entry, name)).sort(),
    );
  }, 60_000);
});

describe('the doc links into the builders', () => {
  it('resolve as an editor resolves them, but for those recorded as not resolving', async () => {
    const { program } = emittedProgram();
    const links = ['filter', 'aggregation'].flatMap(name =>
      linksInto(program, name),
    );
    expect(links.length).toBeGreaterThan(50);
    const counts = new Map<string, number>();
    for (const link of links.filter(each => !each.resolved)) {
      const key = `${link.file} ${link.link}`;
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    const unresolved = [...counts]
      .map(([key, count]) => `${key} ×${count}`)
      .sort();
    await expect(
      JSON.stringify(unresolved, null, 2) + '\n',
    ).toMatchFileSnapshot('../golden/unresolved-builder-links.json');
  }, 60_000);
});
