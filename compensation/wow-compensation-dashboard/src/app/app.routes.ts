import {Routes} from '@angular/router';
import {ToRetryComponent} from "./to-retry/to-retry.component";
import {NextRetryComponent} from "./next-retry/next-retry.component";
import {NonRetryableComponent} from "./non-retryable/non-retryable.component";
import {SucceededComponent} from "./succeeded/succeeded.component";
import {ExecutingComponent} from "./executing/executing.component";

export const routes: Routes = [
  {path: '', pathMatch: 'full', redirectTo: 'to-retry'},
  {path: 'to-retry', component: ToRetryComponent},
  {path: 'executing', component: ExecutingComponent},
  {path: 'next-retry', component: NextRetryComponent},
  {path: 'non-retryable', component: NonRetryableComponent},
  {path: 'succeeded', component: SucceededComponent},
];
