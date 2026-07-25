# Measured Source Git Objects

The benchmark manifests reference temporary harness and pre-squash production commits that are not
guaranteed to remain reachable from a repository ref. Together with its declared prerequisite,
`source-objects.bundle` retains their exact Git commits, trees, and required blobs so the measured
source can still be inspected after those refs are deleted or garbage-collected.

## Bundle boundary

The bundle is intentionally thin relative to the baseline production commit
`a37beee3a0b09b220bb857a24008ca77984f4785`, which is an ancestor of the merged `main` commit.

| Retained head | Commit |
|---|---|
| `evidence-baseline-harness` | `b8dbb0da67dc8b471ffe1e8b5e3418fb744da6ea` |
| `evidence-candidate-harness` | `ad427df203905485279f15e1efc719f2a371dd86` |
| `evidence-final-local` | `a654f626d799d3f7a434e276a90592970cade947` |

The retained ancestry also contains candidate production commit
`c7697de1e62ee5b5d5c3231233424b5122e0193a`. Both harness heads contain the measured
`wow-benchmarks` tree `2c94013d564e636413d9a1bd34632465093d84db`.

`manifest.json` records these identities and the bundle SHA-256. The parent evidence directory's
`SHA256SUMS` also covers the bundle, manifest, and this README.

## Verification

From the parent evidence directory:

```bash
git bundle verify source/source-objects.bundle
git bundle list-heads source/source-objects.bundle
shasum -a 256 -c SHA256SUMS
```

To inspect the retained objects without changing the current checkout, fetch them into a temporary
repository that already contains the prerequisite:

```bash
restore_repo=$(mktemp -d)
git init --quiet "$restore_repo"
git -C "$restore_repo" fetch "$(git rev-parse --show-toplevel)" \
  a37beee3a0b09b220bb857a24008ca77984f4785
git -C "$restore_repo" fetch "$PWD/source/source-objects.bundle" \
  'refs/heads/*:refs/remotes/evidence/*'
git -C "$restore_repo" cat-file -e \
  ad427df203905485279f15e1efc719f2a371dd86^{commit}
git -C "$restore_repo" rev-parse \
  'ad427df203905485279f15e1efc719f2a371dd86^{tree}:wow-benchmarks'
```

The last command must print `2c94013d564e636413d9a1bd34632465093d84db`.
