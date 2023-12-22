import { Component } from '@angular/core';
import {FailedListComponent} from "../failed-list/failed-list.component";
import {FindCategory} from "../api/CompensationClient";

@Component({
  selector: 'app-executing',
  standalone: true,
    imports: [
        FailedListComponent
    ],
  templateUrl: './executing.component.html',
  styleUrl: './executing.component.scss'
})
export class ExecutingComponent {

  protected readonly FindCategory = FindCategory;
}
