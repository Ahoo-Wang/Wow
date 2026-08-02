import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { defineConfig } from "vite";
import tailwindcss from "@tailwindcss/vite";
import { fileURLToPath } from "node:url";

import react, { reactCompilerPreset } from "@vitejs/plugin-react";
import babel from "@rolldown/plugin-babel";

const dashboardRoot = fileURLToPath(new URL(".", import.meta.url));
const dashboardPackage = JSON.parse(
  readFileSync(new URL("./package.json", import.meta.url), "utf8"),
) as { version: string };

function resolveCommitSha(): string {
  const environmentSha =
    process.env.GITHUB_SHA ?? process.env.VITE_APP_COMMIT_SHA;
  if (environmentSha && /^[0-9a-f]{40}$/i.test(environmentSha)) {
    return environmentSha.toLowerCase();
  }

  try {
    return execFileSync("git", ["rev-parse", "HEAD"], {
      cwd: dashboardRoot,
      encoding: "utf8",
    }).trim();
  } catch {
    throw new Error(
      "Unable to resolve the dashboard Git commit. Build from a Git checkout or set GITHUB_SHA.",
    );
  }
}

// https://vite.dev/config/
export default defineConfig({
  define: {
    "import.meta.env.VITE_APP_COMMIT_SHA": JSON.stringify(resolveCommitSha()),
    "import.meta.env.VITE_APP_VERSION": JSON.stringify(
      dashboardPackage.version,
    ),
  },
  build: {
    rolldownOptions: {
      output: {
        codeSplitting: {
          groups: [
            // Keep stable framework code independently cacheable.
            {
              name: "react",
              test: /node_modules[\\/](?:react|react-dom|scheduler)[\\/]/,
              priority: 20,
            },
            {
              name: "router",
              test: /node_modules[\\/]react-router[\\/]/,
              priority: 10,
            },
          ],
        },
      },
    },
  },
  oxc: {
    decorator: {
      legacy: true,
      emitDecoratorMetadata: true,
    },
  },
  plugins: [
    react(),
    babel({
      plugins: [["@babel/plugin-syntax-decorators", { legacy: true }]],
      presets: [reactCompilerPreset()],
    }),
    tailwindcss(),
  ],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
});
