# H5 inputs: the 9.x domains, fixed

The five 9.x domains the H5 evaluation (roadmap 8a.2) migrates with this Skill, copied byte for byte from the `v10`
branch at `a67ece66ec` (side branch `v10-9x-baseline`; the domains match 9.x `v9.9.0`) before the 9.x code was deleted
from `v10` (decision D262). Each module keeps its repository path, so `h5-inputs/<path>` is the 9.x module at `<path>`:

| Domain           | Modules                                                                    |
| ---------------- | -------------------------------------------------------------------------- |
| example          | `example/example-api`, `example/example-domain`, `example/example-server`  |
| transfer (Java)  | `example/transfer/example-transfer-{api,domain,server}`                    |
| compensation     | `compensation/wow-compensation-{api,core,domain,server}`                   |
| view-store       | `view-store/wow-view-store-{api,domain,starter,server}`                    |
| dataset-registry | `dataset-registry/wow-dataset-registry-{api,domain,starter}`               |

Only `build.gradle.kts` and `src/{main,test,integrationTest}` are copied; ABI dumps, Dockerfiles and distribution
configs are not inputs. Nothing builds these files: no Gradle project includes them, and they compile only against 9.x
(`v10-9x-baseline` or a 9.x release). Do not edit them; an eval copies a module into its own workspace before migrating
it. This directory is not an eval case (`scripts/validate_wow_skills.py` skips it).
