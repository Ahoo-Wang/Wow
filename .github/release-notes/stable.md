<!--
The notes of a stable release. package-deploy.yml creates the GitHub release of the tag with this body when none
exists, replacing VERSION and LINE; replace "What is in it" with the notes of typescript/RELEASE_NOTES_TEMPLATE.md
afterwards. An x.Y.0 with breaking changes cannot start this way: release admission requires its notes on GitHub
first, naming every breaking pull request.
-->

## Where it is

- **Maven Central**: `me.ahoo.wow:wow-bom:VERSION` constrains every published module (`wow-api`, `wow-kernel`,
  `wow-runtime`, `wow-spring-boot`, `wow-postgres`, `wow-mongo`, `wow-kafka`, `wow-test`, `wow-tck`, …); the Gradle
  plugins are `id("me.ahoo.wow")`, `id("me.ahoo.wow.api")` and `id("me.ahoo.wow.service")` of `version "VERSION"`, resolved from Maven Central (add `mavenCentral()` to
  `pluginManagement.repositories`).
- **npm**: `npm install @ahoo-wang/wow-client@VERSION`, `@ahoo-wang/wow-generator`, `@ahoo-wang/wow-view-engine`,
  `@ahoo-wang/wow-view-store`, `@ahoo-wang/wow-react`; under the `latest` dist-tag when VERSION is the latest line,
  under `release-LINE` when it is a patch to an older line.
- **Docker**, tags `VERSION` and `LINE`, and `latest` when VERSION is the latest line, pushed by `image-deploy.yml`:
  `ahoowang/wow-ops-server`, `ahoowang/wow-view-store-server`, `ahoowang/wow-analytics-catalog-server` (also on
  `ghcr.io/ahoo-wang/` and `registry.cn-shanghai.aliyuncs.com/ahoo/`). Run them behind your gateway; the view store
  service does no authentication.

## What is in it

<!-- The notes of typescript/RELEASE_NOTES_TEMPLATE.md: Highlights, Breaking, Behaviour changes, each package, JVM. -->
