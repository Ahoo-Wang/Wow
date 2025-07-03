import {Component, EventEmitter, Input, OnInit, Output} from '@angular/core';
import {CompensationClient} from "../api/CompensationClient";
import {NzMessageService} from "ng-zorro-antd/message";
import {RetrySpec} from "../api/ExecutionFailedState";
import {
  FormControl,
  FormGroup,
  FormsModule,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  Validators
} from "@angular/forms";
import {
  NzFormControlComponent,
  NzFormDirective,
  NzFormLabelComponent,
  NzFormModule
} from "ng-zorro-antd/form";
import {NzColDirective} from "ng-zorro-antd/grid";
import {NzInputModule} from 'ng-zorro-antd/input';
import {NzButtonModule} from "ng-zorro-antd/button";
import {NzInputNumberComponent} from "ng-zorro-antd/input-number";

@Component({
  selector: 'app-apply-retry-spec',
  imports: [
    NzFormModule,
    NzFormLabelComponent,
    NzColDirective,
    ReactiveFormsModule,
    NzInputModule,
    NzFormDirective,
    NzFormControlComponent,
    NzButtonModule,
    FormsModule,
    NzInputNumberComponent
  ],
  templateUrl: './apply-retry-spec.component.html',
  styleUrl: './apply-retry-spec.component.scss'
})
export class ApplyRetrySpecComponent implements OnInit {
  @Input({required: true}) id!: string;
  @Input({required: true}) retrySpec!: RetrySpec;
  @Output() afterApply: EventEmitter<boolean> = new EventEmitter<boolean>();
  validateForm!: FormGroup<{
    maxRetries: FormControl<number>;
    minBackoff: FormControl<number>;
    executionTimeout: FormControl<number>;
  }>

  constructor(private compensationClient: CompensationClient,
              private formBuilder: NonNullableFormBuilder,
              private message: NzMessageService) {

  }

  ngOnInit() {
    this.validateForm = this.formBuilder.group({
      maxRetries: [this.retrySpec.maxRetries, [Validators.required]],
      minBackoff: [this.retrySpec.minBackoff, [Validators.required]],
      executionTimeout: [this.retrySpec.executionTimeout, [Validators.required]]
    });
  }

  applyRetrySpec() {
    if (!this.validateForm.valid) {
      return
    }
    const applyRetrySpec = {
      executionTimeout: this.validateForm.controls.executionTimeout.value,
      maxRetries: this.validateForm.controls.maxRetries.value,
      minBackoff: this.validateForm.controls.minBackoff.value,
    }
    this.compensationClient.applyRetrySpec(this.id, applyRetrySpec).subscribe(
      (result) => {
        this.message.success(`ApplyRetrySpec success.`);
        this.afterApply.emit(true);
      },
      (error) => {
        this.message.error(error.error.errorMsg);
        this.afterApply.emit(false);
      }
    );
  }

}
