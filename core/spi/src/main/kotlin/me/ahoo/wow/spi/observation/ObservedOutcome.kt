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

package me.ahoo.wow.spi.observation

/**
 * How an observed stage ended ([Observation.outcome]): its span's `wow.outcome` and the tag of its metric. A value
 * is lower case and one word per concept, whichever stage reports it: a command committed before is `duplicate` to
 * its execution, its append and its timer alike.
 */
enum class ObservedOutcome(val value: String) {
    /** The stage ended without naming another outcome. */
    OK("ok"),

    /** The stage failed. */
    ERROR("error"),

    /** The stage was cancelled. */
    CANCELLED("cancelled"),

    /** A request was admitted. */
    ADMITTED("admitted"),

    /** A request was refused at admission. */
    REFUSED("refused"),

    /** A command committed its events. */
    COMMITTED("committed"),

    /** A command committed before: the commit already in the log is its outcome. */
    DUPLICATE("duplicate"),

    /** A command's decision changed nothing. */
    UNCHANGED("unchanged"),

    /** A command's decision rejected it. */
    REJECTED("rejected"),

    /** A command lost the race for its aggregate's next version. */
    CONTENDED("contended"),

    /** An append met another commit at its version. */
    CONFLICT("conflict"),

    /** The store may or may not have committed. */
    UNKNOWN("unknown"),

    /** A command was sent to another context's command topic, which acknowledged it. */
    SENT("sent"),

    /** A query's count or total came from the cache. */
    CACHED("cached"),
    ;

    override fun toString(): String = value
}
