import { configDefaults, defineConfig, mergeConfig } from 'vitest/config';
import { resolve } from 'path';
import viteConfig from './vite.config';

export default mergeConfig(
  viteConfig,
  defineConfig({
    resolve: {
      alias: {
        '@': resolve(__dirname, 'src'),
      },
    },
    test: {
      clearMocks: true,
      restoreMocks: true,
      unstubGlobals: true,
      coverage: {
        exclude: [
          ...configDefaults.exclude,
          'expected/**',
          'test-output/**',
          // Test support and fixtures are not the code under test; counting
          // them would let a helper's coverage hide a drop in src.
          'test/**',
          'scripts/**',
        ],
        // Held about half a point below what the suite measured on src alone
        // on 2026-10-10 (statements 98.62, branches 95.69, functions 100,
        // lines 99.42): a coverage drop fails CI, a behaviour-preserving
        // refactor does not. Raise them as coverage rises, but never to 100.
        thresholds: {
          statements: 98,
          branches: 95,
          functions: 99.5,
          lines: 99,
        },
      },
    },
  }),
);
