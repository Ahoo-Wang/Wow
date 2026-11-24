// takeover.js, run by lease-storm.sh takeover on the primary: polls every 100 ms until the partitions node DEAD held
// are all owned by others again (PARTITIONS live, none DEAD's), for at most 60 s.
const dbn = typeof DBN !== "undefined" ? DBN : "eshop_catalog";
const partitions = typeof PARTITIONS !== "undefined" ? PARTITIONS : 64;
const coll = db.getSiblingDB(dbn).wow_lease;
const t0 = Date.now();
let firstFree = null;
let done = null;
while (Date.now() - t0 < 60000) {
  const now = Date.now();
  const live = coll.find({ expires_at: { $gt: now } }).toArray();
  const deadHeld = live.filter((l) => l.owner === DEAD).length;
  if (deadHeld === 0 && firstFree === null) firstFree = now - t0;
  if (deadHeld === 0 && live.length === partitions) {
    done = now - t0;
    break;
  }
  sleep(100);
}
print("dead leases gone after " + firstFree + " ms; all " + partitions + " owned by others after " + done + " ms");
