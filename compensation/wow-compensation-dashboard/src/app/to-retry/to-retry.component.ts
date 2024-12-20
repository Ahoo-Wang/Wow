import {Component} from '@angular/core';
import {FailedListComponent} from "../failed-list/failed-list.component";
import {FindCategory} from "../api/CompensationClient";

@Component({
    selector: 'app-to-retry',
    imports: [
        FailedListComponent
    ],
    templateUrl: './to-retry.component.html',
    styleUrl: './to-retry.component.scss'
})
export class ToRetryComponent {

  protected readonly FindCategory = FindCategory;
}
