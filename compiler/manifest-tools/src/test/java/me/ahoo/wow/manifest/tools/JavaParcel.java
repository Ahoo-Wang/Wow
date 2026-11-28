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

package me.ahoo.wow.manifest.tools;

import me.ahoo.wow.api.annotation.Description;
import me.ahoo.wow.api.annotation.Summary;

/** A Java record: an annotation of its component is its field's, which is where Java lets the two stand. */
@Summary("Parcel")
@Description("What a line is shipped in.")
public record JavaParcel(
    @Summary("Weight") @Description("In grams.") int weight,
    String label
) {
    /** A Java class: the two stand on its field. */
    public static class Box {
        @Summary("Width")
        @Description("In millimetres.")
        public int width;
        public int height;
    }
}
