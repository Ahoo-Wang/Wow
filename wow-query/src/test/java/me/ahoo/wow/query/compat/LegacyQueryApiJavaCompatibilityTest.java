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

package me.ahoo.wow.query.compat;

import me.ahoo.wow.api.query.Condition;
import me.ahoo.wow.api.query.PagedList;
import me.ahoo.wow.api.query.Projection;
import me.ahoo.wow.api.query.QueryableKt;
import me.ahoo.wow.api.query.IListQuery;
import me.ahoo.wow.api.query.IPagedQuery;
import me.ahoo.wow.api.query.ISingleQuery;
import me.ahoo.wow.query.QueryService;
import me.ahoo.wow.query.dsl.DslKt;
import me.ahoo.wow.query.event.EventStreamQueryService;
import me.ahoo.wow.query.mask.AggregateDataMaskerKt;
import me.ahoo.wow.query.mask.DataMaskingKt;
import me.ahoo.wow.query.mask.DefaultAggregateDataMasker;
import me.ahoo.wow.query.mask.DynamicDocumentMasker;
import me.ahoo.wow.query.snapshot.SnapshotQueryService;
import kotlin.Unit;

import java.util.List;

final class LegacyQueryApiJavaCompatibilityTest {
    @SuppressWarnings("unused")
    private void compileOnly(
        QueryService<Object> service,
        ISingleQuery singleQuery,
        IListQuery listQuery,
        IPagedQuery pagedQuery,
        Condition condition,
        EventStreamQueryService eventStreamService,
        SnapshotQueryService<Object> snapshotService
    ) {
        service.single(singleQuery);
        service.dynamicSingle(singleQuery);
        service.list(listQuery);
        service.dynamicList(listQuery);
        service.paged(pagedQuery);
        service.dynamicPaged(pagedQuery);
        service.count(condition);

        QueryableKt.isEmpty(new Projection());
        DslKt.singleQuery(dsl -> Unit.INSTANCE);
        me.ahoo.wow.query.event.QueryDslKt.query(singleQuery, eventStreamService);
        me.ahoo.wow.query.snapshot.QueryDslKt.query(singleQuery, snapshotService);
        DataMaskingKt.tryMask(condition);
        DefaultAggregateDataMasker<DynamicDocumentMasker> masker = new DefaultAggregateDataMasker<>(List.of());
        AggregateDataMaskerKt.mask(masker, new PagedList<>(0L, List.of()));
    }
}
