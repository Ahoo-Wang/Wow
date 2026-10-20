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

package me.ahoo.wow.datasetregistry.api

/** The dataset registry's own error codes. The starter registers an HTTP status for each. */
object DatasetRegistryErrorCodes {
    /** The request is not a valid draft, revision or review: its name, table, declaration or fields. HTTP 400. */
    const val DATASET_INVALID = "DatasetInvalid"

    /**
     * The draft cannot be published yet: a string field's sensitivity is not reviewed, or the declaration does not
     * compile against its table. HTTP 409.
     */
    const val DATASET_NOT_PUBLISHABLE = "DatasetNotPublishable"

    /** A dataset of that name is served from a declaration file, which wins over the registry. HTTP 409. */
    const val DATASET_NAME_TAKEN = "DatasetNameTaken"
}
