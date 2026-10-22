<!--
The notes of a 10.x pre-release (R.1, D217). package-deploy.yml creates the GitHub pre-release of the tag with this
body when none exists, replacing VERSION; edit the "What is in it" section afterwards. Keep the three warnings.
-->

> [!WARNING]
> **VERSION is a pre-release of Wow 10, for evaluation only.**
>
> - **No compatibility promise.** The API, configuration, storage and wire formats may change in any later 10.0.0
>   pre-release without a deprecation cycle. The ABI dumps are a review tool until 10.0.0 GA, not a contract.
> - **9.x data migration is a pre-release tool.** `migrate-data` and the offline cut-over have been rehearsed, but
>   they are not for migrating production data: try them on a copy of your 9.x data.
> - **Not for production.** Try it on new projects and test data, and tell us what breaks.

## Where it is

- **Maven Central**, by its explicit version: `me.ahoo.wow:wow-bom:VERSION`
  constrains every published module (`wow-api`, `wow-kernel`, `wow-runtime`, `wow-spring-boot`, `wow-postgres`,
  `wow-mongo`, `wow-kafka`, `wow-test`, `wow-tck`, …); the Gradle plugin is `id("me.ahoo.wow") version "VERSION"`,
  resolved from Maven Central (add `mavenCentral()` to `pluginManagement.repositories`).
- **npm**, under the `alpha` (or `beta`) dist-tag, never `latest`: `npm install @ahoo-wang/wow-client@VERSION`,
  `@ahoo-wang/wow-generator`, `@ahoo-wang/wow-view-engine`, `@ahoo-wang/wow-view-store`, `@ahoo-wang/wow-react`; the
  package roots are Wow 10.
- **Docker**, tag `VERSION` only, never `latest`, pushed by `image-deploy.yml`: `ahoowang/wow-ops-server`,
  `ahoowang/wow-view-store-server`, `ahoowang/wow-analytics-catalog-server` (also on `ghcr.io/ahoo-wang/` and
  `registry.cn-shanghai.aliyuncs.com/ahoo/`).

9.x stays the stable line: `latest` on npm and Docker, and the 9.x Maven modules, do not move.

## What is in it

<!-- The milestones since the previous pre-release, with their pull requests. -->
