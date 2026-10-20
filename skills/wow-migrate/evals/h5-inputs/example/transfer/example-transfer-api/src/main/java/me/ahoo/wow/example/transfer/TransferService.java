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

package me.ahoo.wow.example.transfer;

import me.ahoo.wow.api.annotation.BoundedContext;
import me.ahoo.wow.api.modeling.TenantId;
import me.ahoo.wow.example.transfer.api.CreateAccount;

@BoundedContext(name = TransferService.SERVICE_NAME, alias = TransferService.SERVICE_ALIAS,
        aggregates = {
                @BoundedContext.Aggregate(name = TransferService.ACCOUNT, tenantId = TenantId.DEFAULT_TENANT_ID, packageScopes = {CreateAccount.class})
        }
)
public interface TransferService {
    String SERVICE_NAME = "transfer-service";
    String SERVICE_ALIAS = "transfer";
    String ACCOUNT = "account";
}
