# Security Policy

## Supported Versions

Security fixes target the current stable release line. Fixes for older release lines are evaluated case by case based on severity, affected users, and compatibility risk. The latest stable version is published on the [GitHub Releases](https://github.com/Ahoo-Wang/Wow/releases) page.

This policy covers the TypeScript npm packages built from [`typescript/`](typescript/) as well. They share Wow's version number and release together with it, so the same release lines apply.

## Reporting a Vulnerability

Do not report a suspected vulnerability in a public issue, discussion, or pull request.

Email the maintainers at [ahoowang@qq.com](mailto:ahoowang@qq.com) with:

- the affected Wow version and module;
- for an npm package: the package name and version, and the runtime (Node.js version, or browser name and version);
- the vulnerability type and expected impact;
- reproduction steps or a minimal proof of concept;
- any known mitigations;
- whether the report may be credited publicly.

Remove production credentials, tokens, private customer data, and unrelated sensitive information from the report.

The maintainers will assess the report, coordinate a fix and release when needed, and agree on disclosure timing with the reporter. Please allow time for users to upgrade before publishing technical details that would make exploitation easier.

## Scope

Reports about Wow source code and official release artifacts are in scope. The official release artifacts include the Maven modules and these npm packages:

- [`@ahoo-wang/wow-client`](https://www.npmjs.com/package/@ahoo-wang/wow-client)
- [`@ahoo-wang/wow-react`](https://www.npmjs.com/package/@ahoo-wang/wow-react)
- [`@ahoo-wang/wow-generator`](https://www.npmjs.com/package/@ahoo-wang/wow-generator)
- `@ahoo-wang/wow-view-engine`, once it is published to npm

Vulnerabilities in third-party dependencies should also be reported to the affected upstream project; include the dependency and affected Wow usage when Wow requires a mitigation.
