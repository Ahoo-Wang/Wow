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

import { combineURLs } from '@ahoo-wang/fetcher';
import { isAbsolute, join, relative, resolve, sep } from 'path';
import type { Project, SourceFile } from 'ts-morph';
import { ts } from 'ts-morph';
import { errorMessage, GeneratorError } from '../api/errors';

/** The manifest that records the files a generation wrote, and their hashes. */
export const GENERATION_MANIFEST = '.wow-generator.json';
// compat(fetcher): generations before the move to Wow recorded their files in
// .fetcher-generator.json; read it when the new manifest is absent so a regeneration still
// removes files an older run wrote, and delete it once the new manifest is written.
// Removed in v10.
export const LEGACY_GENERATION_MANIFEST = '.fetcher-generator.json';
const generatedFiles = new WeakMap<
  Project,
  {
    written: Set<string>;
    previous: Map<string, string>;
    stale: Set<string>;
    legacyManifest?: string;
  }
>();

/** Load only explicitly recorded ownership; discard drafts from the last run. */
export function beginGeneration(project: Project, outputDir: string): void {
  const fs = project.getFileSystem();
  outputDir = resolve(fs.getCurrentDirectory(), outputDir);
  const legacyManifestPath = join(outputDir, LEGACY_GENERATION_MANIFEST);
  const legacyManifest = fs.fileExistsSync(legacyManifestPath)
    ? legacyManifestPath
    : undefined;
  let manifestPath = join(outputDir, GENERATION_MANIFEST);
  if (!fs.fileExistsSync(manifestPath) && legacyManifest) {
    manifestPath = legacyManifest;
  }
  const previous = new Map<string, string>();
  if (fs.fileExistsSync(manifestPath)) {
    let manifest;
    try {
      manifest = JSON.parse(fs.readFileSync(manifestPath));
    } catch (error) {
      throw new GeneratorError(
        'output',
        `Invalid generation manifest: ${manifestPath}: ${errorMessage(error)}`,
        { cause: error },
      );
    }
    if (
      manifest?.version !== 1 ||
      !manifest.files ||
      typeof manifest.files !== 'object' ||
      Array.isArray(manifest.files)
    ) {
      throw new GeneratorError(
        'output',
        `Invalid generation manifest: ${manifestPath}`,
      );
    }
    for (const [path, hash] of Object.entries(manifest.files)) {
      const fileName = resolve(outputDir, path);
      assertWithinOutputDir(outputDir, fileName);
      if (
        isAbsolute(path) ||
        !path.endsWith('.ts') ||
        typeof hash !== 'string' ||
        !/^[a-f0-9]{64}$/.test(hash)
      ) {
        throw new GeneratorError(
          'output',
          `Invalid generated file entry in ${manifestPath}: ${path}`,
        );
      }
      previous.set(fileName, hash);
    }
  }
  for (const path of generatedFiles.get(project)?.written ?? []) {
    project.getSourceFile(path)?.forget();
  }
  for (const path of previous.keys()) {
    project.getSourceFile(path)?.forget();
  }
  generatedFiles.set(project, {
    written: new Set(),
    previous,
    stale: new Set(),
    legacyManifest,
  });
}

function fileHash(text: string): string {
  if (!ts.sys.createSHA256Hash) {
    throw new Error('SHA-256 hashing is unavailable in this environment.');
  }
  return ts.sys.createSHA256Hash(text);
}

function isUnchangedGeneratedFile(
  project: Project,
  outputDir: string,
  path: string,
  hash: string,
): boolean {
  const fs = project.getFileSystem();
  if (!fs.fileExistsSync(path)) return false;
  assertWithinOutputDir(fs.realpathSync(outputDir), fs.realpathSync(path));
  return fileHash(fs.readFileSync(path)) === hash;
}

/** Exclude stale owned files from indexes without deleting anything on disk. */
export function forgetStaleGeneratedFiles(
  project: Project,
  outputDir: string,
): void {
  const run = generatedFiles.get(project);
  if (!run) return;
  outputDir = resolve(project.getFileSystem().getCurrentDirectory(), outputDir);
  for (const [path, hash] of run.previous) {
    if (
      !run.written.has(path) &&
      isUnchangedGeneratedFile(project, outputDir, path, hash)
    ) {
      project.getSourceFile(path)?.forget();
      run.stale.add(path);
    }
  }
}

export function getGeneratedFilePaths(
  project: Project,
): ReadonlySet<string> | undefined {
  return generatedFiles.get(project)?.written;
}

/** Save current output before removing unchanged stale files and recording ownership. */
export async function saveGeneration(
  project: Project,
  outputDir: string,
): Promise<void> {
  const run = generatedFiles.get(project);
  if (!run) return;
  outputDir = resolve(project.getFileSystem().getCurrentDirectory(), outputDir);
  const files = [...run.written]
    .sort()
    .map(path => project.getSourceFileOrThrow(path));
  const saved = await Promise.allSettled(files.map(file => file.save()));
  saved.forEach((result, index) => {
    if (result.status === 'rejected') {
      throw outputFailure('write', files[index].getFilePath(), result.reason);
    }
  });
  const fs = project.getFileSystem();
  for (const path of run.stale) {
    if (
      !run.written.has(path) &&
      isUnchangedGeneratedFile(
        project,
        outputDir,
        path,
        run.previous.get(path)!,
      )
    ) {
      await attempt('delete', path, () => fs.delete(path));
    }
  }
  const manifestPath = join(outputDir, GENERATION_MANIFEST);
  const manifest =
    JSON.stringify(
      {
        version: 1,
        files: Object.fromEntries(
          files.map(file => [
            relative(resolve(outputDir), file.getFilePath())
              .split(sep)
              .join('/'),
            fileHash(fs.readFileSync(file.getFilePath())),
          ]),
        ),
      },
      null,
      2,
    ) + '\n';
  await attempt('write', manifestPath, async () => {
    fs.mkdirSync(outputDir);
    await fs.writeFile(manifestPath, manifest);
  });
  const legacyManifest = run.legacyManifest;
  if (legacyManifest && fs.fileExistsSync(legacyManifest)) {
    await attempt('delete', legacyManifest, () => fs.delete(legacyManifest));
  }
}

/**
 * Reports a failure to change the output directory as an `output` error the
 * user can act on, keeping one that already is a {@link GeneratorError}.
 */
function outputFailure(
  action: 'write' | 'delete',
  path: string,
  error: unknown,
): GeneratorError {
  if (error instanceof GeneratorError) return error;
  return new GeneratorError(
    'output',
    `Cannot ${action} ${path}: ${errorMessage(error)}`,
    { cause: error },
  );
}

async function attempt(
  action: 'write' | 'delete',
  path: string,
  change: () => void | Promise<void>,
): Promise<void> {
  try {
    await change();
  } catch (error) {
    throw outputFailure(action, path, error);
  }
}

/**
 * Guards against path traversal: `filePath` derives from a (possibly remote /
 * attacker-controlled) OpenAPI schema key. After joining with `outputDir`, the
 * resolved path must remain INSIDE `outputDir` — otherwise a key like
 * `../../etc/cron.d/pwn` would let `createSourceFile` write or clobber files
 * outside the output directory.
 *
 * `fileName` is the value actually handed to ts-morph (`combineURLs(outputDir,
 * filePath)`), so it is what gets written. We resolve THAT path (not a second
 * join against outputDir) and verify it stays under outputDir — otherwise the
 * checked path and the written path can diverge for relative outputDir values.
 *
 * @throws GeneratorError (`output`) if the resolved path escapes `outputDir`
 */
function assertWithinOutputDir(outputDir: string, fileName: string): void {
  // `combineURLs` yields a URL-style path (forward slashes); normalize to the
  // platform filesystem path before resolving.
  const normalized = fileName.split('/').join(sep);
  const base = resolve(outputDir);
  // fileName already includes the outputDir prefix (it is the write target),
  // so resolve it directly (relative to cwd, or absolute as-is) — do NOT join
  // base again, which would double the prefix and check the wrong location.
  const target = resolve(normalized);
  const rel = relative(base, target);
  // An empty relative path means target === base (a directory, not a file);
  // a path starting with '..' or an absolute path escapes the base.
  const escapes = rel === '' || rel.startsWith('..') || isAbsolute(rel);
  if (escapes) {
    throw new GeneratorError(
      'output',
      `Path traversal detected: "${fileName}" resolves outside the output directory "${outputDir}".`,
    );
  }
}

/**
 * Gets or creates a source file in the project.
 * @param project - The ts-morph project
 * @param outputDir - The output directory
 * @param filePath - The relative file path
 * @returns The source file
 */
export function getOrCreateSourceFile(
  project: Project,
  outputDir: string,
  filePath: string,
): SourceFile {
  const fileName = combineURLs(outputDir, filePath);
  assertWithinOutputDir(outputDir, fileName);
  const file =
    project.getSourceFile(fileName) ??
    project.createSourceFile(fileName, '', {
      overwrite: true,
    });
  const written = generatedFiles.get(project)?.written;
  if (written) {
    const path = file.getFilePath();
    if (!written.has(path)) {
      file.removeText();
      written.add(path);
    }
  }
  return file;
}
