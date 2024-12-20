import { Component } from '@angular/core';
import {FindCategory} from "../api/CompensationClient";
import {FailedListComponent} from "../failed-list/failed-list.component";

@Component({
    selector: 'app-next-retry',
    imports: [
        FailedListComponent
    ],
    templateUrl: './next-retry.component.html',
    styleUrl: './next-retry.component.scss'
})
export class NextRetryComponent {

  protected readonly FindCategory = FindCategory;
}
