/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

// Drops the closing-brace lines from a JaCoCo XML report before ci.yml
// uploads it to Codecov:
//
//   node .github/scripts/jacoco-braces.mjs <report.xml> [repository]
//
// Kotlin gives a function that returns Unit an instruction on its closing
// brace, so JaCoCo reports a line for each `}` (about 4,600 of them across
// the modules). Codecov drops bracket-only lines when it processes a report,
// but not always: on #5182's head (6c90687f34, 2026-10-29) one session kept
// them, and every module's coverage moved by up to 10 points against a base
// whose sessions had none. Dropping them here makes what Codecov stores the
// same whether or not it adjusts the report.
//
// A line is dropped when its source line is `}` alone and it has no branch
// (a brace with code, `foo() }`, a `}` in a string or a comment, and a line
// with a branch stay). The counters of the method it falls in (the one whose
// first line is the last at or before it), of that method's class, of the
// source file, the package and the report lose its line and instructions.
// The report must name each source file by its repository path, its package
// the source file's directory, as test/code-coverage-report's rewrite does.

/** The attributes of the XML start tag [tag], by name. */
const attributesOf = tag =>
  Object.fromEntries(
    [...tag.matchAll(/([\w-]+)="([^"]*)"/g)].map(([, name, value]) => [
      name,
      value,
    ]),
  );

/** Whether a source line is a closing brace and nothing else. */
export const isBraceOnly = text => text.trim() === '}';

/**
 * What a dropped line takes off its counters: a line, missed or covered, and
 * its instructions.
 */
const takenOf = line => {
  const covered = Number(line.ci) > 0;
  return {
    LINE: { missed: covered ? 0 : 1, covered: covered ? 1 : 0 },
    INSTRUCTION: { missed: Number(line.mi), covered: Number(line.ci) },
  };
};

const add = (total, taken) => {
  for (const [type, { missed, covered }] of Object.entries(taken)) {
    const counter = (total[type] ??= { missed: 0, covered: 0 });
    counter.missed += missed;
    counter.covered += covered;
  }
  return total;
};

/** [element]'s own counters (its direct children) less [taken]. */
const subtractCounters = (element, taken) =>
  element.replace(/<counter ([^>]*?)\/>/g, (counter, attributes) => {
    const { type, missed, covered } = attributesOf(attributes);
    const less = taken[type];
    if (!less) return counter;
    return counter
      .replace(/missed="\d+"/, `missed="${Number(missed) - less.missed}"`)
      .replace(/covered="\d+"/, `covered="${Number(covered) - less.covered}"`);
  });

/**
 * [element] with the counters it holds last, right before [closing] (its own,
 * after its children), less [taken].
 */
const subtractTrailing = (element, closing, taken) => {
  const end = element.lastIndexOf(closing);
  const counters = /(?:<counter [^>]*\/>)+$/.exec(element.slice(0, end));
  if (!counters) return element;
  return (
    element.slice(0, counters.index) +
    subtractCounters(counters[0], taken) +
    element.slice(end)
  );
};

/**
 * [xml], a JaCoCo report, without the brace lines of the source files
 * [readSource] reads by repository path (null for one it cannot read, whose
 * lines stay). The number of lines dropped is `dropped`.
 */
export function dropBraceLines(xml, readSource) {
  let dropped = 0;
  const reportTaken = {};
  const packages = xml.replace(
    /<package name="([^"]*)">([\s\S]*?)<\/package>/g,
    (pkg, packageName, body) => {
      const packageTaken = {};
      // Each source file's lines, and what each of its methods loses.
      const methodTaken = new Map();
      const sources = body.replace(
        /<sourcefile name="([^"]*)">([\s\S]*?)<\/sourcefile>/g,
        (sourcefile, name, lines) => {
          const text = readSource(`${packageName}/${name}`);
          if (text == null) return sourcefile;
          const source = text.split('\n');
          const starts = methodStarts(body, name);
          const fileTaken = {};
          const kept = lines.replace(
            /<line ([^>]*?)\/>/g,
            (line, attributes) => {
              const at = attributesOf(attributes);
              const nr = Number(at.nr);
              const branches = Number(at.mb) + Number(at.cb);
              if (branches > 0 || !isBraceOnly(source[nr - 1] ?? ''))
                return line;
              const taken = takenOf(at);
              add(fileTaken, taken);
              const method = starts.filter(start => start.line <= nr).at(-1);
              if (method)
                add(
                  methodTaken.get(method.key) ??
                    methodTaken.set(method.key, {}).get(method.key),
                  taken,
                );
              dropped++;
              return '';
            },
          );
          add(packageTaken, fileTaken);
          return `<sourcefile name="${name}">${subtractCounters(kept, fileTaken)}</sourcefile>`;
        },
      );
      if (Object.keys(packageTaken).length === 0) return pkg;
      add(reportTaken, packageTaken);
      const classes = sources.replace(
        /<class ([^>]*)>([\s\S]*?)<\/class>/g,
        (type, classAttributes) => {
          const className = attributesOf(classAttributes).name;
          const classTaken = {};
          const methods = type.replace(
            /<method ([^>]*)>([\s\S]*?)<\/method>/g,
            (method, attributes) => {
              const { name: methodName, desc } = attributesOf(attributes);
              const taken = methodTaken.get(
                `${className}#${methodName}${desc}`,
              );
              if (!taken) return method;
              add(classTaken, taken);
              return subtractCounters(method, taken);
            },
          );
          return Object.keys(classTaken).length
            ? subtractTrailing(methods, '</class>', classTaken)
            : methods;
        },
      );
      return subtractTrailing(
        `<package name="${packageName}">${classes}</package>`,
        '</package>',
        packageTaken,
      );
    },
  );
  const report = Object.keys(reportTaken).length
    ? subtractTrailing(packages, '</report>', reportTaken)
    : packages;
  return { xml: report, dropped };
}

/** The methods of the classes of source file [name] in a package [body], by first line. */
function methodStarts(body, name) {
  const starts = [];
  for (const [, classAttributes, classBody] of body.matchAll(
    /<class ([^>]*)>([\s\S]*?)<\/class>/g,
  )) {
    const type = attributesOf(classAttributes);
    if (type.sourcefilename !== name) continue;
    for (const [tag] of classBody.matchAll(/<method [^>]*>/g)) {
      const method = attributesOf(tag);
      if (method.line === undefined) continue;
      starts.push({
        line: Number(method.line),
        key: `${type.name}#${method.name}${method.desc}`,
      });
    }
  }
  return starts.sort((a, b) => a.line - b.line);
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const [report, repository = '.'] = process.argv.slice(2);
  if (!report)
    throw new Error('usage: jacoco-braces.mjs <report.xml> [repository]');
  const read = path => {
    try {
      return readFileSync(join(repository, path), 'utf8');
    } catch {
      return null;
    }
  };
  const { xml, dropped } = dropBraceLines(readFileSync(report, 'utf8'), read);
  writeFileSync(report, xml);
  console.log(`${report}: ${dropped} closing-brace lines dropped`);
}
