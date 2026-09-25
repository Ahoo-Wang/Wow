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

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getOrCreateSourceFile } from '../../src/output/generatedFiles';

// Mock ts-morph
vi.mock('ts-morph', () => ({
  Project: vi.fn(),
  SourceFile: vi.fn(),
}));

// NOTE: @ahoo-wang/fetcher is NOT mocked here — the real combineURLs runs so
// that path-joining behavior (slash collapsing) is exercised, not hidden.

const mockProject = {
  getSourceFile: vi.fn(),
  createSourceFile: vi.fn(),
};

const mockDeclaration = {
  addNamedImport: vi.fn(),
};

const mockSourceFile = {
  getImportDeclaration: vi.fn(),
  addImportDeclaration: vi.fn().mockReturnValue(mockDeclaration),
  addNamedImport: vi.fn(),
  getDirectoryPath: vi.fn().mockReturnValue('/src'),
};

describe('generatedFiles', () => {
  beforeEach(() => {
    mockSourceFile.getImportDeclaration.mockClear();
    mockSourceFile.addImportDeclaration.mockClear();
    mockDeclaration.addNamedImport.mockClear();
    mockSourceFile.getDirectoryPath.mockClear();
  });

  describe('getOrCreateSourceFile', () => {
    it('should return existing source file', () => {
      const project = mockProject as any;
      const outputDir = '/output';
      const filePath = 'models/types.ts';

      project.getSourceFile.mockReturnValue(mockSourceFile);

      const result = getOrCreateSourceFile(project, outputDir, filePath);

      expect(project.getSourceFile).toHaveBeenCalledWith(
        '/output/models/types.ts',
      );
      expect(result).toBe(mockSourceFile);
    });

    it('should create new source file if not exists', () => {
      const project = mockProject as any;
      const outputDir = '/output';
      const filePath = 'models/types.ts';

      project.getSourceFile.mockReturnValue(undefined);
      project.createSourceFile.mockReturnValue(mockSourceFile);

      const result = getOrCreateSourceFile(project, outputDir, filePath);

      expect(project.getSourceFile).toHaveBeenCalledWith(
        '/output/models/types.ts',
      );
      expect(project.createSourceFile).toHaveBeenCalledWith(
        '/output/models/types.ts',
        '',
        {
          overwrite: true,
        },
      );
      expect(result).toBe(mockSourceFile);
    });

    // BUG (path traversal): `filePath` derives from a (possibly remote /
    // attacker-controlled) OpenAPI schema key. Without containment, a key like
    // "../../etc/cron.d/pwn" lets `project.createSourceFile(..., { overwrite:
    // true })` write or clobber files OUTSIDE outputDir.
    it('should reject path traversal that escapes the output directory (.. segments)', () => {
      const project = mockProject as any;
      project.getSourceFile.mockReturnValue(undefined);
      project.getSourceFile.mockClear();
      project.createSourceFile.mockClear();

      expect(() =>
        getOrCreateSourceFile(project, '/output', '../../../etc/passwd'),
      ).toThrow();

      // Nothing must have been written outside the output directory.
      expect(project.createSourceFile).not.toHaveBeenCalled();
    });

    // Regression (review): with a RELATIVE output dir (e.g. `src/generated`),
    // the containment check used to double-join the base prefix, so the
    // checked path diverged from the actually-written path. Traversal must be
    // rejected for relative output dirs too.
    it('should reject path traversal for a relative output directory', () => {
      const project = mockProject as any;
      project.getSourceFile.mockReturnValue(undefined);
      project.getSourceFile.mockClear();
      project.createSourceFile.mockClear();

      expect(() =>
        getOrCreateSourceFile(project, 'src/generated', '../../etc/evil'),
      ).toThrow();

      expect(project.createSourceFile).not.toHaveBeenCalled();
    });
  });
});
