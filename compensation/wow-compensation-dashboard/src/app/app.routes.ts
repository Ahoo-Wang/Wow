import { Routes } from '@angular/router';
import {RetryComponent} from "./pages/retry/retry.component";

export const routes: Routes = [
  { path: '', pathMatch: 'full', component: RetryComponent  }
];
