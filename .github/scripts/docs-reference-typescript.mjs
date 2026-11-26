/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */

import { readdirSync } from 'node:fs';
import { join } from 'node:path';

// The experimental declarations of the published TypeScript packages, read
// from their sources as docs-reference-kotlin.mjs reads the Kotlin ones
// (D486 ②): a declaration whose TSDoc carries `@experimental`. Kotlin marks
// the dataset queries, the aggregation queries and their expressions
// `@ExperimentalWowApi`; the TypeScript declarations of the same name carry
// `@experimental`, which `unmarkedCounterparts` holds. The compatibility page
// lists them between `<!-- experimental:begin -->` and
// `<!-- experimental:end -->`.

/** The packages whose sources are read, each with its source directory and API reports. */
export const TYPESCRIPT_PACKAGES = [
  { name: '@ahoo-wang/wow-client', dir: 'typescript/wow-client' },
  { name: '@ahoo-wang/wow-react', dir: 'typescript/wow-react' },
];

/**
 * A module's object of builders that an entry spreads into a public one, by
 * the name its members are reached under: `filter.expression` is written in
 * `expressionFilters`.
 */
export const BUILDER_OWNERS = { expressionFilters: 'filter' };

/** The Kotlin modules whose experimental declarations the TypeScript packages mirror: the query language and the read API. */
export const MIRRORED_MODULES = ['wow-api', 'wow-query-api'];

export const BEGIN = '<!-- experimental:begin -->';
export const END = '<!-- experimental:end -->';

const TOP_LEVEL =
  /^export\s+(?:declare\s+)?(?:abstract\s+)?(interface|type|const|let|function|class|enum|namespace)\s+([A-Za-z_$][\w$]*)/;
const MEMBER =
  /^ {2}(?:(?:public|static|readonly|async|abstract|declare|get|set)\s+)*([A-Za-z_$][\w$]*)\??\s*([<(:=,])/;
const OWNERS = new Set(['interface', 'class', 'enum', 'const']);

/**
 * The declarations of a TypeScript `source`, top-level exports and the
 * members of an exported interface, class, enum or object, each with whether
 * its TSDoc carries `@experimental`: `{ name, kind, marked }`, a member named
 * `Owner.member` (an owner of `BUILDER_OWNERS` by its public name), an
 * overload once.
 */
export function typescriptDeclarations(source, builders = BUILDER_OWNERS) {
  const found = new Map();
  let doc;
  let marked = false;
  let owner;
  let header;
  for (const line of source.split('\n')) {
    if (doc !== undefined) {
      doc += `\n${line}`;
      if (line.includes('*/')) {
        marked = /(?:^|\s)@experimental\b/m.test(doc);
        doc = undefined;
      }
      continue;
    }
    if (/^\s*\/\*\*/.test(line)) {
      if (line.includes('*/', line.indexOf('/**') + 3))
        marked = /(?:^|\s)@experimental\b/.test(line);
      else doc = line;
      continue;
    }
    if (!line.trim() || /^\s*(?:\/\/|\*|@)/.test(line)) continue;
    const top = TOP_LEVEL.exec(line);
    if (top) {
      const [, kind, name] = top;
      add(found, name, kind, marked);
      owner = undefined;
      header = OWNERS.has(kind) ? { name, kind } : undefined;
      if (header && /[{]\s*$/.test(line)) [owner, header] = [header, undefined];
    } else if (/^\S/.test(line)) {
      // The end of a body, or of a header written over several lines.
      if (header && /[{]\s*$/.test(line)) [owner, header] = [header, undefined];
      else if (/^[}\])]/.test(line)) owner = header = undefined;
    } else if (owner) {
      const member = MEMBER.exec(line);
      if (member) {
        const [, name, next] = member;
        const kind =
          owner.kind === 'enum'
            ? 'enum member'
            : next === '(' || next === '<'
              ? 'method'
              : 'property';
        add(
          found,
          `${builders[owner.name] ?? owner.name}.${name}`,
          kind,
          marked,
        );
      }
    }
    marked = false;
  }
  return [...found.values()];
}

function add(found, name, kind, marked) {
  const known = found.get(name);
  if (known) known.marked ||= marked;
  else found.set(name, { name, kind, marked });
}

const sourceFiles = (root, dir) => {
  try {
    return readdirSync(join(root, dir), { recursive: true })
      .map(file => String(file).split('\\').join('/'))
      .filter(file => /\.tsx?$/.test(file) && !file.endsWith('.d.ts'))
      .map(file => `${dir}/${file}`)
      .sort();
  } catch {
    return [];
  }
};

/** The names an entry's API report exports: `export interface X`, `export const y`. */
const reportedNames = report =>
  new Set(
    [
      ...report.matchAll(
        /^export (?:declare )?(?:abstract )?(?:interface|type|const|let|function|class|enum|namespace) ([A-Za-z_$][\w$]*)/gm,
      ),
    ].map(match => match[1]),
  );

/**
 * The declarations of each package in `packages`, `{ name, kind, marked,
 * entry }`, with the entry that exports each (`@ahoo-wang/wow-client/dsl`),
 * read from the entry's API report (`test/api/<entry>.api.md`, `root` the
 * package itself). A declaration no entry exports is left out: an internal
 * one, which no host imports.
 */
export function typescriptPackageDeclarations(
  root,
  read,
  packages = TYPESCRIPT_PACKAGES,
) {
  return packages.flatMap(({ name: pkg, dir }) => {
    const reports = readdirSync(join(root, dir, 'test/api'))
      .filter(file => file.endsWith('.api.md'))
      .sort((a, b) =>
        a === 'root.api.md' ? -1 : b === 'root.api.md' ? 1 : a.localeCompare(b),
      )
      .map(file => {
        const report = file.slice(0, -'.api.md'.length);
        return {
          entry: report === 'root' ? pkg : `${pkg}/${report}`,
          names: reportedNames(read(`${dir}/test/api/${file}`)),
        };
      });
    const declarations = new Map();
    for (const file of sourceFiles(root, `${dir}/src`))
      for (const declaration of typescriptDeclarations(read(file))) {
        const owner = declaration.name.split('.')[0];
        const entry = reports.find(({ names }) => names.has(owner))?.entry;
        if (!entry) continue;
        const key = `${entry} ${declaration.name}`;
        const known = declarations.get(key);
        if (known) known.marked ||= declaration.marked;
        else declarations.set(key, { ...declaration, entry });
      }
    return [...declarations.values()];
  });
}

/** The experimental declarations of `declarations`, by entry, then name. */
export function experimentalOf(declarations) {
  return declarations
    .filter(declaration => declaration.marked)
    .sort(
      (a, b) => a.entry.localeCompare(b.entry) || a.name.localeCompare(b.name),
    );
}

/**
 * The TypeScript declarations named as an experimental Kotlin declaration of
 * a mirrored module is (`AggregationQuery`, `FilterOperator.EXPRESSION`, the
 * name past the package) that do not carry `@experimental`.
 */
export function unmarkedCounterparts(kotlin, declarations) {
  const experimental = new Set(
    kotlin
      .filter(({ artifact }) => MIRRORED_MODULES.includes(artifact))
      .map(({ name }) => {
        const parts = name.split('.');
        const first = parts.findIndex(part => /^[A-Z]/.test(part));
        return parts.slice(first < 0 ? parts.length - 1 : first).join('.');
      }),
  );
  return declarations.filter(
    ({ name, marked }) => !marked && experimental.has(name),
  );
}

const WORDS = {
  en: {
    generated:
      "<!-- Generated by .github/scripts/docs-reference.mjs from the packages' sources; regenerate with `pnpm docs:reference`. Do not edit. -->",
    head: '| Declaration | Kind | Entry |',
    none: 'No declaration is experimental.',
  },
  zh: {
    generated:
      '<!-- 由 .github/scripts/docs-reference.mjs 依据各包的源码生成；用 `pnpm docs:reference` 重新生成。请勿手改。 -->',
    head: '| 声明 | 种类 | 入口 |',
    none: '没有实验性的声明。',
  },
};

/** The block of the compatibility page that lists `experimental`, between its markers. */
export function experimentalBlock(locale, experimental) {
  const words = WORDS[locale];
  return [
    BEGIN,
    words.generated,
    '',
    ...(experimental.length
      ? [
          words.head,
          '| --- | --- | --- |',
          ...experimental.map(
            ({ name, kind, entry }) =>
              `| \`${name}\` | ${kind} | \`${entry}\` |`,
          ),
        ]
      : [words.none]),
    '',
    END,
  ].join('\n');
}

/** `text`, the compatibility page at `path`, with its block written from `experimental`. */
export function withExperimental(text, locale, experimental, path) {
  const start = text.indexOf(BEGIN);
  const end = text.indexOf(END, start);
  if (start < 0 || end < 0)
    throw new Error(`${path} must hold ${BEGIN} and then ${END}`);
  return (
    text.slice(0, start) +
    experimentalBlock(locale, experimental) +
    text.slice(end + END.length)
  );
}
