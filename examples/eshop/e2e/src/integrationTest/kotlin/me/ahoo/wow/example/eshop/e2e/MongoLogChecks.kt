/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.example.eshop.e2e

/**
 * The MongoDB arm's reads of a context's store as mongosh programs: the GA verdict runs them against the cluster's
 * replica set (`MongoReads`), and [MongoLogChecksTest] its log's integrity against the logs two nodes write.
 */
object MongoLogChecks {
    /**
     * A mongosh program that runs [script], a function's body, on [database] at [uri]; it prints the answer as relaxed
     * Extended JSON.
     */
    fun program(uri: String, database: String, script: String): String =
        "const db = connect('$uri').getSiblingDB('$database'); " +
            "print(EJSON.stringify((() => { $script })(), { relaxed: true }))"

    /**
     * The integrity violations of [context]'s log, each `{kind, …}`: an aggregate whose versions are not 1..max once
     * each, and a sequence of a partition two streams took across the context's collections.
     *
     * Only streams count: a block another node sealed has a placeholder at each sequence it left in each collection it
     * may write, so that node's late write meets a taken position whichever its collection. A placeholder so repeats
     * across the collections of a context of several aggregate types, beside a stream or another placeholder, and the
     * tail delivers the stream. A sequence missing between two that are taken is a block still open, not a loss.
     */
    fun violations(context: String): String =
        """
        const collections = db.wow_log_aggregate.find({ context: '$context' }).toArray().map(r => r._id);
        const out = [];
        for (const c of collections) {
          db.getCollection(c).aggregate([
            { ${'$'}match: { aggregate_id: { ${'$'}exists: true } } },
            { ${'$'}group: { _id: { t: '${'$'}tenant_id', a: '${'$'}aggregate_id' }, n: { ${'$'}sum: 1 },
              d: { ${'$'}addToSet: '${'$'}version' }, min: { ${'$'}min: '${'$'}version' }, max: { ${'$'}max: '${'$'}version' } } },
            { ${'$'}match: { ${'$'}expr: { ${'$'}or: [ { ${'$'}ne: ['${'$'}n', '${'$'}max'] }, { ${'$'}ne: ['${'$'}min', 1] },
              { ${'$'}ne: [{ ${'$'}size: '${'$'}d' }, '${'$'}n'] } ] } } }
          ]).forEach(g => out.push({ kind: 'versions', collection: c, aggregateId: g._id.a, versions: g.n, min: g.min, max: g.max }));
        }
        if (collections.length > 0) {
          const streams = { ${'$'}match: { aggregate_id: { ${'$'}exists: true } } };
          const [first, ...rest] = collections;
          db.getCollection(first).aggregate([
            streams,
            ...rest.map(c => ({ ${'$'}unionWith: { coll: c, pipeline: [streams] } })),
            { ${'$'}group: { _id: { p: '${'$'}partition', s: '${'$'}sequence' }, n: { ${'$'}sum: 1 } } },
            { ${'$'}match: { n: { ${'$'}gt: 1 } } }
          ], { allowDiskUse: true }).forEach(g => out.push({ kind: 'sequence taken twice', partition: g._id.p, sequence: g._id.s, times: g.n }));
        }
        return out;
        """.trimIndent()
}
