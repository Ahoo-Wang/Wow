import {ListQuery} from "./ListQuery";
import {Conditions, SortDirection} from "./Query";

const VERSION = "version";
export const MAX_VERSION = 666666666;

function eventStreamVersionedNextQuery(aggregateId: string, endVersion: number = MAX_VERSION, limit: number = 0): ListQuery {
  return {
    projection: {include: ["_id", "id", VERSION, "createTime", "body.id", "body.name"], exclude: []},
    condition: Conditions.and(
      [
        Conditions.eq("aggregateId", aggregateId),
        Conditions.lte(VERSION, endVersion)
      ]
    ),
    sort: [{field: VERSION, direction: SortDirection.DESC}],
    limit: limit,
  };
}

export function eventStreamVersionedPagedQuery(aggregateId: string, maxVersion: number = MAX_VERSION, pageIndex: number = 1, pageSize: number = 10): ListQuery {
  if (pageIndex == 1) {
    return eventStreamVersionedNextQuery(aggregateId, maxVersion, pageSize);
  }
  let endVersion = maxVersion - (pageIndex - 1) * pageSize;
  return eventStreamVersionedNextQuery(aggregateId, endVersion, pageSize);
}
