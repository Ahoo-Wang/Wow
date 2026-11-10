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
 * `node scripts/patch-coverage-local.mjs [--base <ref>] [--rerun] <module>…` computes, before a push, the patch
 * coverage `ci-gate` will compute for the committed changes of the current branch:
 *
 *   node scripts/patch-coverage-local.mjs postgres
 *   node scripts/patch-coverage-local.mjs --base origin/main :kernel :runtime
 *
 * 1. Runs each module's `test` and, when it has one, `integrationTest`, with JaCoCo, through a Gradle init script
 *    of its own (no build file changes), and writes one XML report per module from both execution files.
 * 2. Names each source file of a report by its repository path, its package the source root's path plus the package
 *    directory, as `test/code-coverage-report` does for the reports CI uploads.
 * 3. Drops the closing-brace lines (`.github/scripts/jacoco-braces.mjs`), as CI does.
 * 4. Runs `.github/scripts/patch-coverage.mjs` itself over the reports, against the merge base of `--base` (default
 *    `origin/main`, fetched by you, not by the script) and `HEAD`.
 *
 * The verdict is the gate's: covered ÷ counted over the changed main-source lines, a partial line counted as
 * uncovered, at least 89%. Only committed changes count, as in CI; the working tree's are named and left out.
 *
 * It exits 1 when the verdict is `fail`, and also when the patch has main-source lines but no report matched any of
 * their files: the gate would read `none` for a run whose reports did not reach it, and so does `patch-coverage.mjs`
 * here when the reports name their files otherwise. Changed main-source files in modules not given are named.
 *
 * `--rerun` deletes the modules' execution files and runs their tests again; otherwise Gradle's up-to-date tests
 * keep the execution files of their last run.
 *
 * Testcontainers needs Docker for `integrationTest`; on OrbStack, set `DOCKER_HOST` as AGENTS.md says.
 */

import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, relative } from 'node:path';
import { pathToFileURL } from 'node:url';

/** The Gradle init script: a `patchCoverageReport` task on each project, writing its XML and its source roots. */
const INIT_SCRIPT = `
allprojects {
    plugins.withId("jacoco") {
        afterEvaluate {
            val out = providers.gradleProperty("patchCoverageOut").orNull ?: return@afterEvaluate
            val modules = providers.gradleProperty("patchCoverageModules").orNull.orEmpty().split(',')
            if (path !in modules) return@afterEvaluate
            val main = extensions.findByType(SourceSetContainer::class.java)?.findByName("main") ?: return@afterEvaluate
            val rerun = providers.gradleProperty("patchCoverageRerun").isPresent
            val testTasks = listOf("test", "integrationTest").mapNotNull { tasks.findByName(it) as? Test }
            if (rerun) {
                testTasks.forEach { task ->
                    task.outputs.upToDateWhen { false }
                    task.doFirst {
                        task.extensions.getByType(JacocoTaskExtension::class.java).destinationFile?.delete()
                    }
                }
            }
            val name = path.removePrefix(":").replace(':', '_')
            tasks.register("patchCoverageReport", JacocoReport::class.java) {
                dependsOn(testTasks)
                executionData.setFrom(
                    testTasks.mapNotNull { it.extensions.getByType(JacocoTaskExtension::class.java).destinationFile },
                )
                sourceDirectories.setFrom(main.allSource.srcDirs)
                classDirectories.setFrom(main.output.classesDirs)
                reports {
                    xml.required.set(true)
                    html.required.set(false)
                    csv.required.set(false)
                    xml.outputLocation.set(file("$out/$name.xml"))
                }
                val roots = main.allSource.srcDirs
                doLast { file("$out/$name.roots").writeText(roots.joinToString("\\n") { it.absolutePath }) }
            }
        }
    }
}
`;

const run = (command, args, options = {}) =>
  execFileSync(command, args, { encoding: 'utf8', maxBuffer: 256 * 1024 * 1024, ...options }).trim();

/** [xml], each source file moved into a package named by its source root's repository path and its package path. */
export function nameByRepositoryPath(xml, roots, repository, isFile = existsSync) {
  let unmatched = 0;
  const text = xml.replace(/<package name="([^"]*)">([\s\S]*?)<\/package>/g, (pkg, packagePath, body) => {
    const classes = [...body.matchAll(/<class [^>]*?(?:\/>|>[\s\S]*?<\/class>)/g)].map(([element]) => element);
    const sources = [...body.matchAll(/<sourcefile name="([^"]*)">[\s\S]*?<\/sourcefile>/g)];
    const byRoot = new Map();
    for (const [element, name] of sources) {
      const root = roots.find(candidate => isFile(join(candidate, packagePath, name)));
      if (!root) unmatched++;
      const key = root ? relative(repository, root).split('\\').join('/') : null;
      const group = byRoot.get(key) ?? byRoot.set(key, { classes: [], sources: [] }).get(key);
      group.sources.push(element);
      group.classes.push(...classes.filter(type => type.includes(`sourcefilename="${name}"`)));
    }
    return [...byRoot]
      .map(([root, group]) => {
        const name = root ? `${root}/${packagePath}` : packagePath;
        return `<package name="${name}">${group.classes.join('')}${group.sources.join('')}</package>`;
      })
      .join('');
  });
  return { text, unmatched };
}

/** The modules' Gradle paths, `:a:b`, from `a:b`, `:a:b` or `a`. */
export const modulePath = name => (name.startsWith(':') ? name : `:${name}`);

function parseArguments(argv) {
  const options = { base: 'origin/main', rerun: false, modules: [] };
  for (let i = 0; i < argv.length; i++) {
    const argument = argv[i];
    if (argument === '--base') options.base = argv[++i];
    else if (argument === '--rerun') options.rerun = true;
    else if (argument === '--help' || argument === '-h') options.help = true;
    else options.modules.push(modulePath(argument));
  }
  return options;
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  if (options.help || !options.modules.length || !options.base) {
    console.error('usage: node scripts/patch-coverage-local.mjs [--base <ref>] [--rerun] <module>…');
    process.exit(2);
  }
  const repository = run('git', ['rev-parse', '--show-toplevel']);
  const gate = await import(pathToFileURL(join(repository, '.github/scripts/patch-coverage.mjs')).href);
  const braces = join(repository, '.github/scripts/jacoco-braces.mjs');
  const base = run('git', ['merge-base', options.base, 'HEAD'], { cwd: repository });
  const head = run('git', ['rev-parse', 'HEAD'], { cwd: repository });

  const dirty = run('git', ['status', '--porcelain', '--untracked-files=no'], { cwd: repository })
    .split('\n')
    .map(line => line.slice(3))
    .filter(path => path && gate.isMainSource(path));
  if (dirty.length)
    console.warn(`Uncommitted main-source changes, not in the patch (commit them to count them): ${dirty.join(', ')}`);

  const work = mkdtempSync(join(tmpdir(), 'patch-coverage-'));
  const out = join(work, 'out');
  const reports = join(work, 'reports', 'jvm');
  mkdirSync(out, { recursive: true });
  mkdirSync(reports, { recursive: true });
  const init = join(work, 'patch-coverage.init.gradle.kts');
  writeFileSync(init, INIT_SCRIPT);
  const gradle = spawnSync(
    join(repository, 'gradlew'),
    [
      '-I',
      init,
      `-PpatchCoverageOut=${out}`,
      `-PpatchCoverageModules=${options.modules.join(',')}`,
      ...(options.rerun ? ['-PpatchCoverageRerun=true'] : []),
      '--continue',
      ...options.modules.map(path => `${path}:patchCoverageReport`),
    ],
    { cwd: repository, stdio: 'inherit' },
  );
  if (gradle.status !== 0) {
    console.error(`Gradle failed (exit ${gradle.status}): the patch coverage is not computed.`);
    process.exit(1);
  }

  const allRoots = [];
  let unmatched = 0;
  for (const report of readdirSync(out).filter(name => name.endsWith('.xml'))) {
    const roots = readFileSync(join(out, report.replace(/\.xml$/, '.roots')), 'utf8').split('\n').filter(Boolean);
    allRoots.push(...roots);
    const named = nameByRepositoryPath(readFileSync(join(out, report), 'utf8'), roots, repository);
    unmatched += named.unmatched;
    const target = join(reports, report);
    writeFileSync(target, named.text);
    run('node', [braces, target, repository], { cwd: repository });
  }
  if (unmatched) console.warn(`${unmatched} source files of the reports were found in no source root; they keep their package path.`);

  const changed = gate.changedLines(run('git', ['diff', '-U0', '--no-renames', '--no-color', base, head], { cwd: repository }));
  const changedMain = [...changed.keys()].filter(gate.isMainSource);
  const rootPaths = allRoots.map(root => relative(repository, root).split('\\').join('/') + '/');
  const outside = changedMain.filter(path => !rootPaths.some(root => path.startsWith(root)));
  if (outside.length) console.warn(`Changed main-source files in modules not given, not measured: ${outside.join(', ')}`);

  const coverage = new Map();
  for (const report of readdirSync(reports)) gate.addJacoco(coverage, readFileSync(join(reports, report), 'utf8'));

  const output = join(work, 'output');
  writeFileSync(output, '');
  const verdict = spawnSync('node', [join(repository, '.github/scripts/patch-coverage.mjs'), join(work, 'reports')], {
    cwd: repository,
    env: { ...process.env, BASE: base, HEAD_SHA: head, GITHUB_OUTPUT: output, GITHUB_STEP_SUMMARY: '' },
    stdio: 'inherit',
  });
  if (verdict.status !== 0) process.exit(verdict.status ?? 1);
  const state = /^patch=(\w+)$/m.exec(readFileSync(output, 'utf8'))?.[1];
  rmSync(work, { recursive: true, force: true });

  // A report that names no file under the modules' source roots was not named by repository path, and every
  // changed line would read as instrumented by no report: the gate's `none`, for a run that measured nothing.
  const measured = changedMain.filter(path => !outside.includes(path));
  const named = [...coverage.keys()].some(path => rootPaths.some(root => path.startsWith(root)));
  if (measured.length && !named) {
    console.error(
      `The patch has main-source lines in ${measured.length} file(s) of the modules given, and the reports matched no file under their source roots: failing rather than reading this as "none".`,
    );
    process.exit(1);
  }
  if (state === 'fail') process.exit(1);
  console.log(`patch coverage against ${options.base} (merge base ${base.slice(0, 10)}): ${state}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main();
