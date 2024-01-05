import { Component } from '@angular/core';
import {FailedListComponent} from "../failed-list/failed-list.component";
import {FindCategory} from "../api/CompensationClient";

@Component({
  selector: 'app-unrecoverable',
  standalone: true,
    imports: [
        FailedListComponent
    ],
  templateUrl: './unrecoverable.component.html',
  styleUrl: './unrecoverable.component.scss'
})
export class UnrecoverableComponent {

  protected readonly FindCategory = FindCategory;
}
