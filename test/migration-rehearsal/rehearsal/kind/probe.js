// The latency probe of 9.x's MongoDB (kind.sh mongo_load_start), run by mongosh in its pod on wow_example_db: every
// 200 ms it reads one order's streams by id, as 9.x does to serve a command, and prints the read's milliseconds,
// until kind.sh mongo_load_stop marks its run, @RUN@, stopped.
const ids = db.order_event_stream.aggregate([{ $sample: { size: 200 } }, { $project: { aggregateId: 1 } }])
  .toArray()
  .map((stream) => stream.aggregateId);
const stopped = db.getSiblingDB('rehearsal_probe').stop;
const now = () => (typeof performance !== 'undefined' ? performance.now() : Date.now());
let read = 0;
while (ids.length > 0 && stopped.countDocuments({ run: '@RUN@' }) === 0) {
  const at = now();
  db.order_event_stream.find({ aggregateId: ids[read++ % ids.length] }).sort({ version: 1 }).toArray();
  print((now() - at).toFixed(3));
  sleep(200);
}
