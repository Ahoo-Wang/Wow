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

import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';

/**
 * Dependency rules from docs/design.md §4, checked on the TypeScript AST so that
 * multi-line, type-only, re-exported and dynamic imports are all seen. A dynamic
 * import counts only where its specifier is constant — a string literal or a
 * template without substitutions; one built from a variable, `import(target)`,
 * is not recorded and would slip past these rules. Layers absent from `src/`
 * pass trivially, so the rules hold from the empty tree on.
 *
 * Freedom from DOM globals in the headless layers is enforced by the compiler:
 * `tsconfig.headless.json` type-checks them without the `DOM` library (see the
 * `test:type` script). This file only checks module boundaries.
 */
const LAYERS = [
  'model',
  'filter',
  'record',
  'analysis',
  'dashboard',
  'runtime',
  'store',
  'react',
  'ui',
] as const;
type Layer = (typeof LAYERS)[number];
type Location = Layer | 'root';

const ALLOWED: Record<Location, readonly Layer[]> = {
  model: [],
  filter: ['model'],
  record: ['model', 'filter'],
  analysis: ['model', 'filter'],
  dashboard: ['model', 'filter'],
  runtime: ['model', 'filter', 'record', 'analysis', 'dashboard', 'store'],
  store: ['model'],
  react: [
    'model',
    'filter',
    'record',
    'analysis',
    'dashboard',
    'runtime',
    'store',
  ],
  ui: [...LAYERS],
  root: [
    'model',
    'filter',
    'record',
    'analysis',
    'dashboard',
    'runtime',
    'store',
  ],
};

/**
 * Dependencies that must be type-only imports of one port module: the runtime
 * depends on the `ViewStore` contract, never on a store implementation.
 */
const PORTS: Partial<Record<Location, Partial<Record<Layer, string>>>> = {
  runtime: { store: 'store/ViewStore' },
};

/** Layers (and the root entry) that must stay free of React. */
const HEADLESS: readonly Location[] = [
  'root',
  'model',
  'filter',
  'record',
  'analysis',
  'dashboard',
  'runtime',
  'store',
];

const WOW = '@ahoo-wang/fetcher-wow';
const src = resolve(dirname(fileURLToPath(import.meta.url)), '../src');
const wowSrc = resolve(src, '../../wow/src');

/**
 * Dependencies that may be imported outside `ui`. Every other runtime or peer
 * dependency declared in package.json is derived as UI-only (React peers may
 * also be used by the `react` layer), so a new React dependency cannot reach a
 * headless layer without being listed here explicitly.
 */
const HEADLESS_DEPENDENCIES: Record<string, readonly Location[]> = {
  [WOW]: ['root', 'model', 'filter', 'record', 'analysis', 'runtime'],
  culori: ['analysis'],
  dayjs: ['filter', 'record', 'analysis', 'runtime', 'ui'],
  dequal: ['runtime'],
};

const manifest = JSON.parse(
  readFileSync(resolve(src, '../package.json'), 'utf8'),
) as {
  name: string;
  dependencies?: Record<string, string>;
  peerDependencies?: Record<string, string>;
};

const SELF = manifest.name;

/** Third-party packages and the only locations allowed to import them. */
const THIRD_PARTY: Record<string, readonly Location[]> = Object.fromEntries([
  ...Object.keys(manifest.dependencies ?? {}).map(
    name => [name, HEADLESS_DEPENDENCIES[name] ?? ['ui']] as const,
  ),
  ...Object.keys(manifest.peerDependencies ?? {}).map(
    name => [name, ['react', 'ui']] as const,
  ),
]);

interface Import {
  specifier: string;
  typeOnly: boolean;
  /** Named bindings as declared by the exporting module (aliases resolved). */
  names: string[];
  namespace: boolean;
}

interface SourceFile {
  path: string;
  location: Location;
  imports: Import[];
  hasJsx: boolean;
}

function walk(directory: string): string[] {
  if (!existsSync(directory)) return [];
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return walk(path);
    return /\.(ts|tsx)$/.test(entry.name) && !/\.(test|d)\./.test(entry.name)
      ? [path]
      : [];
  });
}

function parse(path: string): ts.SourceFile {
  return ts.createSourceFile(
    path,
    readFileSync(path, 'utf8'),
    ts.ScriptTarget.Latest,
    true,
    path.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS,
  );
}

function locationOf(path: string): Location {
  const [first] = relative(src, path).split(sep);
  return (LAYERS as readonly string[]).includes(first)
    ? (first as Layer)
    : 'root';
}

function importsOf(file: ts.SourceFile): Import[] {
  const imports: Import[] = [];
  const visit = (node: ts.Node) => {
    if (
      ts.isImportDeclaration(node) &&
      ts.isStringLiteral(node.moduleSpecifier)
    ) {
      const clause = node.importClause;
      const bindings = clause?.namedBindings;
      const named =
        bindings && ts.isNamedImports(bindings) ? bindings.elements : [];
      imports.push({
        specifier: node.moduleSpecifier.text,
        // A default import is a value binding, so a mixed
        // `import helper, { type T } from ...` is not type-only.
        typeOnly:
          clause?.isTypeOnly === true ||
          (clause?.name === undefined &&
            named.length > 0 &&
            named.every(element => element.isTypeOnly)),
        names: named.map(
          element => (element.propertyName ?? element.name).text,
        ),
        namespace: bindings !== undefined && ts.isNamespaceImport(bindings),
      });
    } else if (
      ts.isExportDeclaration(node) &&
      node.moduleSpecifier &&
      ts.isStringLiteral(node.moduleSpecifier)
    ) {
      const named =
        node.exportClause && ts.isNamedExports(node.exportClause)
          ? node.exportClause.elements
          : [];
      imports.push({
        specifier: node.moduleSpecifier.text,
        typeOnly:
          node.isTypeOnly ||
          (named.length > 0 && named.every(element => element.isTypeOnly)),
        names: named.map(
          element => (element.propertyName ?? element.name).text,
        ),
        namespace: named.length === 0,
      });
    } else if (
      ts.isCallExpression(node) &&
      node.expression.kind === ts.SyntaxKind.ImportKeyword &&
      node.arguments[0] &&
      // A template without substitutions is a constant specifier too.
      (ts.isStringLiteral(node.arguments[0]) ||
        ts.isNoSubstitutionTemplateLiteral(node.arguments[0]))
    ) {
      imports.push({
        specifier: node.arguments[0].text,
        typeOnly: false,
        names: [],
        namespace: true,
      });
    } else if (
      // `type T = import('./x').Y` is an ImportTypeNode, not a call.
      ts.isImportTypeNode(node) &&
      ts.isLiteralTypeNode(node.argument) &&
      ts.isStringLiteral(node.argument.literal)
    ) {
      const qualifier = node.qualifier;
      imports.push({
        specifier: node.argument.literal.text,
        typeOnly: true,
        names: qualifier && ts.isIdentifier(qualifier) ? [qualifier.text] : [],
        namespace: qualifier === undefined,
      });
    }
    ts.forEachChild(node, visit);
  };
  visit(file);
  return imports;
}

/**
 * Package entries map onto layers, so a self-reference such as
 * `@ahoo-wang/fetcher-view-engine/ui` is resolved like an internal import
 * instead of passing as an unknown external module.
 */
const SELF_ENTRIES: Record<string, string> = {
  '.': 'index.ts',
  './react': 'react',
  './ui': 'ui',
};

function targetOf(from: SourceFile, specifier: string): string | null {
  if (specifier.startsWith('.'))
    return relative(src, resolve(dirname(from.path), specifier));
  if (specifier.startsWith('@/')) return specifier.slice(2);
  if (specifier === SELF || specifier.startsWith(`${SELF}/`)) {
    const entry = specifier === SELF ? '.' : `.${specifier.slice(SELF.length)}`;
    // An unknown entry resolves to the root, where the strictest rules apply.
    return SELF_ENTRIES[entry] ?? 'index.ts';
  }
  return null;
}

function targetLocation(from: SourceFile, specifier: string): Location | null {
  const target = targetOf(from, specifier);
  return target === null ? null : locationOf(join(src, target));
}

function isPort(from: SourceFile, specifier: string, port: string): boolean {
  const target = targetOf(from, specifier);
  return target !== null && target.replace(/\.(ts|js)$/, '') === port;
}

/** Every export of the Wow workspace package whose JSDoc carries `@deprecated`. */
function deprecatedWowExports(): Set<string> {
  const names = new Set<string>();
  for (const path of walk(wowSrc)) {
    for (const statement of parse(path).statements) {
      const exported = ts
        .getModifiers(statement as ts.HasModifiers)
        ?.some(modifier => modifier.kind === ts.SyntaxKind.ExportKeyword);
      if (!exported) continue;
      if (
        !ts
          .getJSDocTags(statement)
          .some(tag => tag.tagName.text === 'deprecated')
      )
        continue;
      if (ts.isVariableStatement(statement)) {
        for (const declaration of statement.declarationList.declarations)
          if (ts.isIdentifier(declaration.name))
            names.add(declaration.name.text);
      } else if (
        (ts.isFunctionDeclaration(statement) ||
          ts.isClassDeclaration(statement) ||
          ts.isInterfaceDeclaration(statement) ||
          ts.isTypeAliasDeclaration(statement) ||
          ts.isEnumDeclaration(statement)) &&
        statement.name
      ) {
        names.add(statement.name.text);
      }
    }
  }
  return names;
}

function containsJsx(node: ts.Node): boolean {
  if (
    ts.isJsxElement(node) ||
    ts.isJsxSelfClosingElement(node) ||
    ts.isJsxFragment(node)
  )
    return true;
  return ts.forEachChild(node, containsJsx) === true;
}

const files: SourceFile[] = walk(src).map(path => {
  const file = parse(path);
  return {
    path,
    location: locationOf(path),
    imports: importsOf(file),
    hasJsx: containsJsx(file),
  };
});
const at = (location: Location) =>
  files.filter(file => file.location === location);
const describePath = (file: SourceFile) => relative(src, file.path);
const LOCATIONS: readonly Location[] = ['root', ...LAYERS];

describe('architecture', () => {
  // Registry popups portal to document.body, out of the surface that holds
  // the theme tokens; ui/popups.tsx carries the theme out with them. A file
  // that reaches a popup module another way renders its contents with no
  // theme, a transparent menu over the table, and nothing else notices. So
  // the rule is read off the import records rather than the source text: a
  // namespace import, `export *`, a re-export, a dynamic import and a `.ts`
  // file all count. Only a type-only import, which renders nothing, is free.
  it('takes every popup content from ui/popups.tsx, not from the registry', () => {
    const modules = [
      'combobox',
      'dialog',
      'dropdown-menu',
      'popover',
      'select',
      'tooltip',
    ].map(name => join('ui', 'components', name));
    const contents = new Set([
      'ComboboxContent',
      'DialogContent',
      'DropdownMenuContent',
      'PopoverContent',
      'SelectContent',
      'TooltipContent',
    ]);
    const exempt = (file: SourceFile) =>
      /^ui\/(components|lib)\//.test(describePath(file)) ||
      describePath(file) === join('ui', 'popups.tsx');
    const violations = files
      .filter(file => !exempt(file))
      .flatMap(file =>
        file.imports
          .filter(entry => {
            const target = targetOf(file, entry.specifier)?.replace(
              /\.(tsx?|js)$/,
              '',
            );
            return (
              !entry.typeOnly &&
              target !== undefined &&
              modules.includes(target) &&
              (entry.namespace || entry.names.some(name => contents.has(name)))
            );
          })
          .map(entry => `${describePath(file)} -> ${entry.specifier}`),
      );
    expect(violations).toEqual([]);
  });

  it('has a root entry', () => {
    expect(existsSync(join(src, 'index.ts'))).toBe(true);
  });

  it('places every source file in a known layer or at the root', () => {
    const misplaced = at('root')
      .map(describePath)
      .filter(path => path.includes(sep));
    expect(misplaced).toEqual([]);
  });

  it.each(LOCATIONS)(
    '%s imports only the layers it is allowed to',
    location => {
      const violations = at(location).flatMap(file =>
        file.imports
          .filter(({ specifier }) => {
            const target = targetLocation(file, specifier);
            return (
              target !== null &&
              target !== location &&
              !(ALLOWED[location] as readonly string[]).includes(target)
            );
          })
          .map(({ specifier }) => `${describePath(file)} -> ${specifier}`),
      );
      expect(violations).toEqual([]);
    },
  );

  it.each(
    Object.entries(PORTS).flatMap(([from, ports]) =>
      Object.entries(ports ?? {}).map(
        ([target, port]) => [from as Location, target as Layer, port] as const,
      ),
    ),
  )(
    '%s depends on %s only through type-only imports of %s',
    (from, target, port) => {
      const violations = at(from).flatMap(file =>
        file.imports
          .filter(
            ({ specifier, typeOnly }) =>
              targetLocation(file, specifier) === target &&
              !(typeOnly && isPort(file, specifier, port)),
          )
          .map(({ specifier }) => `${describePath(file)} -> ${specifier}`),
      );
      expect(violations).toEqual([]);
    },
  );

  it.each(HEADLESS)('%s is free of React', location => {
    const violations = at(location).flatMap(file =>
      file.imports
        .filter(
          ({ specifier }) =>
            /^react(-dom)?(\/|$)/.test(specifier) ||
            ['react', 'ui'].includes(targetLocation(file, specifier) ?? ''),
        )
        .map(({ specifier }) => `${describePath(file)} -> ${specifier}`),
    );
    expect(violations).toEqual([]);
  });

  it.each(HEADLESS)('%s contains no JSX', location => {
    // `jsx: react-jsx` injects an implicit `react/jsx-runtime` import that no
    // specifier check can see, so JSX itself is a React dependency here.
    const violations = at(location)
      .filter(file => file.path.endsWith('.tsx') || file.hasJsx)
      .map(describePath);
    expect(violations).toEqual([]);
  });

  it('lists only declared dependencies as headless-capable', () => {
    const declared = new Set([
      ...Object.keys(manifest.dependencies ?? {}),
      ...Object.keys(manifest.peerDependencies ?? {}),
    ]);
    expect(
      Object.keys(HEADLESS_DEPENDENCIES).filter(name => !declared.has(name)),
    ).toEqual([]);
  });

  /**
   * The mirror of the rule below it. A dependency nothing imports is still
   * installed by everyone who installs this package, and the ones that
   * accumulate here are the ones a plan named before the code needed them —
   * `@tanstack/react-table` sat in the manifest while the design said, in
   * as many words, that the table does not use it yet.
   */
  it('declares only packages something imports', () => {
    const imported = new Set(
      files.flatMap(file => file.imports.map(({ specifier }) => specifier)),
    );
    const used = (name: string) =>
      [...imported].some(
        specifier => specifier === name || specifier.startsWith(`${name}/`),
      );

    expect(
      Object.keys(manifest.dependencies ?? {}).filter(name => !used(name)),
    ).toEqual([]);
  });

  it('imports only packages this manifest declares', () => {
    // An undeclared bare specifier may still resolve through the workspace
    // root or a transitive dependency, and would then escape every rule below.
    const declared = Object.keys(THIRD_PARTY);
    const violations = files.flatMap(file =>
      file.imports
        .map(({ specifier }) => specifier)
        .filter(
          specifier =>
            !specifier.startsWith('.') &&
            !specifier.startsWith('@/') &&
            !specifier.startsWith('node:') &&
            specifier !== SELF &&
            !specifier.startsWith(`${SELF}/`) &&
            !declared.some(
              name => specifier === name || specifier.startsWith(`${name}/`),
            ),
        )
        .map(specifier => `${describePath(file)} -> ${specifier}`),
    );
    expect(violations).toEqual([]);
  });

  it.each(Object.entries(THIRD_PARTY))(
    '%s is imported only from its designated layers',
    (name, locations) => {
      const violations = files
        .filter(
          file =>
            !(locations as readonly string[]).includes(file.location) &&
            file.imports.some(
              ({ specifier }) =>
                specifier === name || specifier.startsWith(`${name}/`),
            ),
        )
        .map(describePath);
      expect(violations).toEqual([]);
    },
  );

  describe('Wow protocol', () => {
    const deprecated = deprecatedWowExports();

    it('derives the deprecated export set from the Wow sources', () => {
      for (const name of ['Condition', 'PagedQuery', 'pagedQuery', 'Operator'])
        expect(deprecated.has(name)).toBe(true);
      expect(deprecated.has('FilterExpression')).toBe(false);
    });

    it('imports Wow only from its root entry', () => {
      // The published subpaths (query locales) belong to the deprecated
      // Condition API, and a subpath import would bypass the name check below.
      const violations = files.flatMap(file =>
        file.imports
          .filter(({ specifier }) => specifier.startsWith(`${WOW}/`))
          .map(({ specifier }) => `${describePath(file)} -> ${specifier}`),
      );
      expect(violations).toEqual([]);
    });

    it('imports Wow by name so every binding can be checked', () => {
      const violations = files
        .filter(file =>
          file.imports.some(
            ({ specifier, namespace }) => specifier === WOW && namespace,
          ),
        )
        .map(describePath);
      expect(violations).toEqual([]);
    });

    it('never imports deprecated Wow APIs', () => {
      const violations = files.flatMap(file =>
        file.imports
          .filter(({ specifier }) => specifier === WOW)
          .flatMap(({ names }) => names.filter(name => deprecated.has(name)))
          .map(name => `${describePath(file)} imports ${name}`),
      );
      expect(violations).toEqual([]);
    });
  });
});
