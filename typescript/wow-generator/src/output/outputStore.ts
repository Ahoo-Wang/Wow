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

import { isAbsolute, join, relative, resolve, sep } from 'path';
import type { Project, SourceFile } from 'ts-morph';
import { ts } from 'ts-morph';
import { errorMessage, GeneratorError } from '../api/errors';
import { combinePaths } from '../naming/paths';

/** The manifest that records the files a generation wrote, and their hashes. */
export const GENERATION_MANIFEST = '.wow-generator.json';
// compat(fetcher): generations before the move to Wow recorded their files in
// .fetcher-generator.json; read it when the new manifest is absent so a regeneration still
// removes files an older run wrote, and delete it once the new manifest is written.
// Removed in v10.
export const LEGACY_GENERATION_MANIFEST = '.fetcher-generator.json';

/**
 * The output directory of one generation: which files it owns, which of the
 * files an earlier run wrote it may remove, and writing them.
 *
 * The manifest (`.wow-generator.json`) records every file a run wrote and
 * the hash it wrote. A file of the last run that this run does not write is
 * stale; it is removed only if its bytes are still the ones recorded, so a
 * hand-edited file is never lost. A file the manifest does not name is never
 * touched.
 *
 * One store per run: {@link open} reads the manifest, {@link claim} hands
 * out the files the run writes, {@link commit} writes them, removes the stale
 * ones and writes the new manifest.
 */
export class OutputStore {
  /** The files this run writes, by absolute path. */
  private readonly written = new Set<string>();
  /** Files of the last run this run does not write and may remove. */
  private readonly stale = new Set<string>();

  private constructor(
    private readonly project: Project,
    /** The output directory as the caller named it. */
    private readonly outputPath: string,
    /** The output directory, absolute. */
    private readonly outputDir: string,
    /** The files of the last run, with the hash each was written with. */
    private readonly previous: ReadonlyMap<string, string>,
    /** A pre-Wow manifest to delete once the new one is written. */
    private readonly legacyManifest: string | undefined,
  ) {}

  /**
   * Reads the manifest of an output directory, the pre-Wow one when the new
   * one is absent, and takes the files it names out of the project: a run
   * writes its files afresh.
   *
   * @param project - The project the run writes into
   * @param outputDir - The output directory, absolute or relative to the
   * project's working directory
   * @param last - The store of the last run into the same project, whose
   * files, written or only drafted, leave the project too
   * @throws GeneratorError (`output`) when the manifest is not one, or names a
   * file outside the output directory
   */
  static open(
    project: Project,
    outputDir: string,
    last?: OutputStore,
  ): OutputStore {
    const fs = project.getFileSystem();
    const outputPath = outputDir;
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
    for (const path of last?.written ?? []) {
      project.getSourceFile(path)?.forget();
    }
    for (const path of previous.keys()) {
      project.getSourceFile(path)?.forget();
    }
    return new OutputStore(
      project,
      outputPath,
      outputDir,
      previous,
      legacyManifest,
    );
  }

  /** The files this run writes, by absolute path. */
  get files(): ReadonlySet<string> {
    return this.written;
  }

  /**
   * The source file of a path under a directory of the output, claimed for
   * this run: the first claim empties it, and {@link commit} writes it.
   *
   * @param filePath - The path, relative to `directory`
   * @param directory - The output directory as the caller named it, or a
   * directory under it
   * @throws GeneratorError (`output`) when the path resolves outside it
   */
  claim(filePath: string, directory: string = this.outputPath): SourceFile {
    const fileName = combinePaths(directory, filePath);
    assertWithinOutputDir(directory, fileName);
    const file =
      this.project.getSourceFile(fileName) ??
      this.project.createSourceFile(fileName, '', { overwrite: true });
    const path = file.getFilePath();
    if (!this.written.has(path)) {
      file.removeText();
      this.written.add(path);
    }
    return file;
  }

  /**
   * Takes the files of the last run this run does not write out of the
   * project, when they are still as that run wrote them, so the indexes do
   * not export them; {@link commit} removes them. Nothing on disk changes.
   */
  forgetStale(): void {
    for (const [path, hash] of this.previous) {
      if (!this.written.has(path) && this.isUnchanged(path, hash)) {
        this.project.getSourceFile(path)?.forget();
        this.stale.add(path);
      }
    }
  }

  /**
   * Writes the files this run claimed, then removes the stale ones that are
   * still as the last run wrote them, then writes the manifest and deletes a
   * pre-Wow one.
   *
   * @param signal - Stops the run after the files are written: nothing is
   * removed and the manifest stays the last run's, so an interrupted run
   * never records files it did not finish
   * @throws GeneratorError (`output`) when writing or removing a file fails;
   * the signal's reason when it was aborted
   */
  async commit(signal?: AbortSignal): Promise<void> {
    signal?.throwIfAborted();
    const files = [...this.written]
      .sort()
      .map(path => this.project.getSourceFileOrThrow(path));
    const saved = await Promise.allSettled(files.map(file => file.save()));
    saved.forEach((result, index) => {
      if (result.status === 'rejected') {
        throw outputFailure('write', files[index].getFilePath(), result.reason);
      }
    });
    signal?.throwIfAborted();
    const fs = this.project.getFileSystem();
    for (const path of this.stale) {
      if (
        !this.written.has(path) &&
        this.isUnchanged(path, this.previous.get(path)!)
      ) {
        await attempt('delete', path, () => fs.delete(path));
      }
    }
    const manifestPath = join(this.outputDir, GENERATION_MANIFEST);
    const manifest =
      JSON.stringify(
        {
          version: 1,
          files: Object.fromEntries(
            files.map(file => [
              relative(this.outputDir, file.getFilePath()).split(sep).join('/'),
              fileHash(fs.readFileSync(file.getFilePath())),
            ]),
          ),
        },
        null,
        2,
      ) + '\n';
    await attempt('write', manifestPath, async () => {
      fs.mkdirSync(this.outputDir);
      await fs.writeFile(manifestPath, manifest);
    });
    const legacyManifest = this.legacyManifest;
    if (legacyManifest && fs.fileExistsSync(legacyManifest)) {
      await attempt('delete', legacyManifest, () => fs.delete(legacyManifest));
    }
  }

  /**
   * Tells whether a file of the last run is still as it wrote it: present,
   * inside the output directory once links are followed, and of the hash the
   * manifest records.
   */
  private isUnchanged(path: string, hash: string): boolean {
    const fs = this.project.getFileSystem();
    if (!fs.fileExistsSync(path)) return false;
    assertWithinOutputDir(
      fs.realpathSync(this.outputDir),
      fs.realpathSync(path),
    );
    return fileHash(fs.readFileSync(path)) === hash;
  }
}

function fileHash(text: string): string {
  if (!ts.sys.createSHA256Hash) {
    throw new Error('SHA-256 hashing is unavailable in this environment.');
  }
  return ts.sys.createSHA256Hash(text);
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
 * `fileName` is the value actually handed to ts-morph (`combinePaths(outputDir,
 * filePath)`), so it is what gets written. We resolve THAT path (not a second
 * join against outputDir) and verify it stays under outputDir — otherwise the
 * checked path and the written path can diverge for relative outputDir values.
 *
 * @throws GeneratorError (`output`) if the resolved path escapes `outputDir`
 */
function assertWithinOutputDir(outputDir: string, fileName: string): void {
  // `combinePaths` yields a URL-style path (forward slashes); normalize to the
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
