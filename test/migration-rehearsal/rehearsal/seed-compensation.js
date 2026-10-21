// The 9.x compensation service's unresolved executions of the rehearsal (roadmap 8c.1): `count` failed executions of
// the 9.x CartSaga.onOrderCreated, one per order the example created, written as the compensation service keeps them,
// the snapshot of its `execution_failed` aggregate (migrate-data's README, "Compensation"). The 9.x compensation
// service receives failures over Kafka, which the rehearsal's 9.x example runs without, so the rehearsal writes them
// here, with the shape of migrate-data's fixtures and the ids of the orders the 9.x example stored.
//
// mongosh <uri> --eval 'var count = 100, example = "wow_example_db", compensation = "compensation_db"' seed-compensation.js
const source = db.getSiblingDB(example);
const target = db.getSiblingDB(compensation).getCollection("execution_failed_snapshot");
const now = Date.now();
let index = 0;
source
  .getCollection("order_event_stream")
  .find({ version: 1 }, { aggregateId: 1, tenantId: 1, body: 1, createTime: 1 })
  .sort({ aggregateId: 1 })
  .limit(count)
  .forEach((stream) => {
    const id = `rehearsal-exec-${String(index).padStart(6, "0")}`;
    const status = index % 5 === 4 ? "PREPARED" : "FAILED";
    target.replaceOne(
      { _id: id },
      {
        _id: id,
        contextName: "compensation-service",
        aggregateName: "execution_failed",
        tenantId: "(0)",
        ownerId: "",
        spaceId: "",
        version: 1,
        eventId: `${id}-event`,
        firstOperator: "",
        operator: "",
        firstEventTime: stream.createTime,
        eventTime: stream.createTime,
        state: {
          id: id,
          error: {
            errorCode: "IllegalState",
            errorMsg: `the order ${stream.aggregateId} failed in CartSaga`,
            stackTrace: "java.lang.IllegalStateException: rehearsal",
            succeeded: false,
          },
          eventId: {
            id: stream.body[0].id,
            aggregateId: {
              contextName: "example-service",
              aggregateName: "order",
              aggregateId: stream.aggregateId,
              tenantId: stream.tenantId,
            },
            version: 1,
          },
          executeAt: stream.createTime,
          function: {
            functionKind: "EVENT",
            contextName: "example-service",
            processorName: "CartSaga",
            name: "onOrderCreated",
          },
          isBelowRetryThreshold: true,
          isRetryable: true,
          recoverable: index % 3 === 0 ? "RECOVERABLE" : "UNKNOWN",
          retrySpec: { maxRetries: 10, minBackoff: 180, executionTimeout: 120 },
          retryState: {
            retries: index % 4,
            retryAt: stream.createTime,
            timeoutAt: stream.createTime + 120000,
            nextRetryAt: now + 3600000,
          },
          status: status,
        },
        snapshotTime: now,
        tags: {},
        deleted: false,
      },
      { upsert: true },
    );
    index++;
  });
print(JSON.stringify({ command: "seed-compensation", executions: index }));
