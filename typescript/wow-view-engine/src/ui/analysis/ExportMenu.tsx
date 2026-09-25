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

import { useRef, useState } from 'react';
import {
  DownloadIcon,
  FileImageIcon,
  FileSpreadsheetIcon,
  XIcon,
} from 'lucide-react';
import { LineAlert } from '../alerts.js';
import { AlertAction, AlertTitle } from '../components/alert.js';
import { Button } from '../components/button.js';
import {
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../components/dropdown-menu.js';
import { ExportDialog, type ExportWindowProps } from '../ExportDialog.js';
import {
  DialogMenuItem,
  HandOffMenu,
  HandOffMenuContent,
} from '../HandOffMenu.js';
import { IconButton, IconTooltip } from '../IconButton.js';
import { useViewMessages } from '../MessagesProvider.js';
import { ToolbarItem } from '../toolbar.js';
import type { ChartImageOffer, ImageFormat } from './imageExport.js';

/** Each picture's item, by its full catalogue key. */
const FORMATS = [
  ['png', 'label.export.image-png'],
  ['svg', 'label.export.image-svg'],
] as const satisfies readonly (readonly [ImageFormat, string])[];

/**
 * The picture items of an export menu (D33 Q58): PNG and SVG, each a
 * press that hands the file over at once — there is nothing to choose and
 * nothing to wait for. Shared by the result's toolbar and a panel's 「⋯」.
 */
export function ImageMenuItems({ offer }: { offer: ChartImageOffer }) {
  const messages = useViewMessages();
  return FORMATS.map(([format, key]) => (
    <DropdownMenuItem
      key={format}
      data-slot={`export-image-${format}`}
      onClick={() => offer.take(format)}
    >
      <FileImageIcon />
      {messages.label(key)}
    </DropdownMenuItem>
  ));
}

/**
 * A picture that could not be made, said on one line with a way to put the
 * line away — a PNG drawn onto a canvas can be refused by the page (its
 * `img-src`), where the SVG still goes.
 */
export function ImageFailed({ offer }: { offer: ChartImageOffer }) {
  const messages = useViewMessages();
  if (offer.failed === null) return null;
  return (
    <LineAlert
      tone="error"
      frame="bare"
      className="basis-full"
      data-slot="export-image-failed"
    >
      <AlertTitle>{messages.label('label.export.image-failed')}</AlertTitle>
      <AlertAction>
        <IconButton
          label={messages.label('label.dialog.close')}
          variant="ghost"
          size="icon-xs"
          onClick={offer.dismiss}
        >
          <XIcon />
        </IconButton>
      </AlertAction>
    </LineAlert>
  );
}

/**
 * The analysis toolbar's 「导出」 while a chart is drawn (D25 Q28, D33
 * Q58): one menu — 「导出数据…」, which opens the export window over the
 * groups, and the chart as a PNG or an SVG. Over a table there is no
 * picture, and the toolbar keeps the plain button that opens the window.
 */
export function ExportMenu({
  data,
  image,
}: {
  data: ExportWindowProps | null;
  image: ChartImageOffer;
}) {
  const messages = useViewMessages();
  const [open, setOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  return (
    <>
      <HandOffMenu>
        <IconTooltip
          label={messages.label('label.export.title')}
          render={
            <ToolbarItem
              render={
                <DropdownMenuTrigger
                  ref={trigger}
                  data-control="export"
                  render={<Button variant="outline" size="icon-sm" />}
                />
              }
            />
          }
        >
          <DownloadIcon />
        </IconTooltip>
        <HandOffMenuContent align="end" className="min-w-48">
          <DropdownMenuGroup>
            {data && (
              <DialogMenuItem
                data-slot="export-data"
                onClick={() => setOpen(true)}
              >
                <FileSpreadsheetIcon />
                {messages.label('label.export.data')}
              </DialogMenuItem>
            )}
            <ImageMenuItems offer={image} />
          </DropdownMenuGroup>
        </HandOffMenuContent>
      </HandOffMenu>
      {data && (
        <ExportDialog
          {...data}
          open={open}
          onOpenChange={setOpen}
          finalFocus={() => trigger.current ?? true}
        />
      )}
    </>
  );
}
