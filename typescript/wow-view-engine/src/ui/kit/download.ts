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

/** A file the user is being handed, before the browser is asked to take it. */
export interface DownloadedFile {
  name: string;
  /** What the file holds: text — a CSV, an SVG — or bytes, a PNG. */
  content: string | Blob;
  /** The media type the blob is made with; the browser names the file by it. */
  type: string;
}

/**
 * Hands a file to the browser.
 *
 * The whole of the DOM this feature needs, in one function and in `/ui`: a
 * blob, an object URL and an anchor that clicks itself. Revoking the URL in a
 * `finally` rather than on a timer is safe because the click is synchronous —
 * the browser has taken the blob by the time this returns — and it is a
 * `finally` so that a click the browser refuses frees the blob all the same,
 * rather than holding the whole file in memory for the rest of the session.
 */
export function downloadFile(file: DownloadedFile): void {
  const url = URL.createObjectURL(
    new Blob([file.content], { type: file.type }),
  );
  const anchor = document.createElement('a');
  try {
    anchor.href = url;
    anchor.download = file.name;
    anchor.rel = 'noopener';
    anchor.style.display = 'none';
    document.body.append(anchor);
    anchor.click();
  } finally {
    anchor.remove();
    URL.revokeObjectURL(url);
  }
}

/**
 * Everything a file system refuses, the control characters included — a
 * newline in a view's title would otherwise reach a `Content-Disposition`
 * header through whatever the file is uploaded to next.
 *
 * The control characters are `\p{Cc}` rather than a range written out,
 * because a class holding one of them is what `no-control-regex` is about
 * however it is spelled: the escapes would need the same disable comment the
 * raw bytes had, and the raw bytes made the file unsearchable besides (B2).
 */
const UNSAFE_IN_A_NAME = /[\\/:*?"<>|\p{Cc}]+/gu;

/**
 * How much of a title a file name carries. Long enough that the titles
 * people actually write arrive whole, and short enough that the day, the
 * extension and whatever a browser adds for a second copy of the same file
 * all fit inside the 255 bytes a file system allows — a name that does not
 * fit is refused or silently truncated by the download itself, which is how
 * a title long enough would have cost the day and the extension (B12).
 */
const TITLE_MAX = 80;

/**
 * A file name from a view's title and a day: `订单待办-2026-09-20.csv`.
 *
 * The title is the user's own words, so it is kept as it is but for what a
 * file system will not take and for its length; a title that is blank or all
 * punctuation leaves the day to name the file by itself rather than a file
 * called `-.csv`.
 */
export function fileName(
  title: string,
  day: string,
  extension: string,
): string {
  // Collapsed as well as replaced: `2026/09: 待办` would otherwise leave the
  // two spaces of "the slash, then the space that followed the colon".
  const safe = title
    .replace(UNSAFE_IN_A_NAME, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, TITLE_MAX)
    // Trimmed again: the cut may land in the middle of a gap, and a name
    // reading `订单 -2026-09-20.csv` is the sanitising the trim above
    // already refused to leave behind.
    .trim();
  return `${safe === '' ? day : `${safe}-${day}`}.${extension}`;
}
