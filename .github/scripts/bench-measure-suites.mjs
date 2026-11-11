/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { armsOfRuns, walkRuns, walkShareRuns } from './bench-measure.mjs';
import { indexSuites } from './bench-index.mjs';
import { sinkSuites } from './bench-sink.mjs';

/**
 * The suites of the `measure` kind, by name, on bench.mjs's arms: the access scale checks (the dataset's queries, the
 * walk's share and cost), the sink group (sinkSuites) and the index group (indexSuites). bench.mjs spreads them into its SUITES where they stood.
 */
export function measureSuites({
  HEAD_PG,
  BASE_PG,
  HEAD_MONGO,
  BASE_MONGO,
  ACCESS_CLAIMS_PG,
  ACCESS_DATASET_PG,
}) {
  return {
    'access-dataset-ch': {
      group: 'access-dataset',
      kind: 'measure',
      measure: 'dataset-access',
      arms: [ACCESS_CLAIMS_PG, ACCESS_DATASET_PG],
      answers:
        "What an access policy costs a dataset query (D390 C4): one node's list page, count and aggregation of a dataset of 1,000,000 rows over 100,000 accounts on ClickHouse, without a policy of the account and with one whose predicate compares each account's current owner with an analyst's claims (a tenth of them), read by a semi-join on the Sink's `_state`.",
    },
    'access-walk-share-pg': {
      group: 'access-walk',
      kind: 'measure',
      measure: 'walk',
      runs: walkShareRuns(HEAD_PG, BASE_PG),
      arms: armsOfRuns(walkShareRuns(HEAD_PG, BASE_PG)),
      answers:
        "Which share of the log store the access walk may take under live commands (X-77), on PostgreSQL: the data set's accounts of 10 streams each, written by a first node, then without their attributes walked by a second while 200 E1 deposits a second run; every deposit into an account that keeps its attributes and the store settled before each run, so the walk is the only stamping; the head's walk at each share against the baseline's at its default pace, between two runs of the baseline with nothing to walk (main's A/A band). The default is the largest share within the band.",
    },
    'access-walk-share-mongo': {
      group: 'access-walk',
      kind: 'measure',
      measure: 'walk',
      runs: walkShareRuns(HEAD_MONGO, BASE_MONGO),
      arms: armsOfRuns(walkShareRuns(HEAD_MONGO, BASE_MONGO)),
      answers: 'The walk share on MongoDB, as access-walk-share-pg.',
    },
    'access-walk-pg': {
      group: 'access-walk',
      kind: 'measure',
      measure: 'walk',
      runs: walkRuns(HEAD_PG, BASE_PG),
      arms: armsOfRuns(walkRuns(HEAD_PG, BASE_PG)),
      answers:
        "What the access walk costs live commands (X-77), on PostgreSQL: as access-walk-share-pg, the head's walk at its defaults against the baseline's, ABBA between two runs of the baseline with nothing to walk (main's A/A band). The head passes when each of those runs' p99 is within the band. Beside the verdict: the head's depositing into the accounts it stamps first, both builds' depositing into accounts not stamped (the wow.access subscription also stamps each at its first deposit), and the head's walk without deposits.",
    },
    'access-walk-mongo': {
      group: 'access-walk',
      kind: 'measure',
      measure: 'walk',
      runs: walkRuns(HEAD_MONGO, BASE_MONGO),
      arms: armsOfRuns(walkRuns(HEAD_MONGO, BASE_MONGO)),
      answers:
        'The access walk under live commands on MongoDB, as access-walk-pg.',
    },
    ...sinkSuites(HEAD_PG),
    ...indexSuites(HEAD_MONGO),
  };
}
