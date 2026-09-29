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

import { useId, useRef, useState, type ReactNode, type Ref } from 'react';
import { PlusIcon, Trash2Icon } from 'lucide-react';
import type { FinalFocus } from './commands.js';
import {
  isSafeContentUrl,
  type NewContentPanel,
} from '../../dashboard/index.js';
import {
  MAX_MARKDOWN_LENGTH,
  MAX_PANEL_LINKS,
  type DashboardContentPanel,
} from '../../model/index.js';
import { Button } from '../components/button.js';
import {
  Dialog,
  DialogClose,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../components/dialog.js';
import {
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from '../components/field.js';
import { Input } from '../components/input.js';
import { Textarea } from '../components/textarea.js';
import { ToggleGroup, ToggleGroupItem } from '../components/toggle-group.js';
import { IconButton } from '../IconButton.js';
import type { MessageKey } from '../messages.js';
import { keptKey, useViewMessages } from '../MessagesProvider.js';
import { DialogContent } from '../popups.js';

/** The content panels a form edits; a heading is named in place instead. */
export type EditedKind = 'markdown' | 'image' | 'links';

/** What the form is for: a new panel of a kind, or one already on the board. */
export type ContentTarget =
  | { mode: 'add'; kind: EditedKind }
  | {
      mode: 'edit';
      panelId: string;
      panel: Extract<DashboardContentPanel, { kind: EditedKind }>;
    };

export interface ContentEditorProps {
  /** What the last opening was for; kept while it closes. */
  target: ContentTarget | null;
  open: boolean;
  onClose(): void;
  /** Where the keyboard goes once it closes — the menu that asked is gone. */
  finalFocus: FinalFocus;
  /**
   * The panel as the form left it: every member, a blank optional one
   * `undefined` — so an edit takes a cleared address off rather than
   * leaving an empty one the kernel would refuse.
   */
  onSubmit(content: NewContentPanel & { kind: EditedKind }): void;
}

const HEADINGS: Record<EditedKind, readonly [MessageKey, MessageKey]> = {
  markdown: ['label.content.markdown.add', 'label.content.markdown.edit'],
  image: ['label.content.image.add', 'label.content.image.edit'],
  links: ['label.content.links.add', 'label.content.links.edit'],
};

/**
 * The small form a note, a picture or a list of links is written in (D22 A):
 * minimal, and complete — what the kernel would refuse (an address it does
 * not load, a link without words) is said at the field before the panel is
 * written, never after. A title of its own is optional, as on every panel.
 */
export function ContentEditor({
  target,
  open,
  onClose,
  finalFocus,
  onSubmit,
}: ContentEditorProps) {
  const messages = useViewMessages();
  const kind = target?.mode === 'add' ? target.kind : target?.panel.kind;
  const form = useRef<HTMLFormElement>(null);
  return (
    <Dialog open={open} onOpenChange={next => !next && onClose()}>
      <DialogContent
        data-slot="content-editor"
        // The keyboard starts in the first box, whatever the kind: typing is
        // what the form is for. A list of links would otherwise open on its
        // first link's 「移除」, the first thing that takes the keyboard.
        initialFocus={() =>
          form.current?.querySelector<HTMLElement>('input, textarea') ?? true
        }
        finalFocus={finalFocus}
      >
        {/* Remounted on each opening: a form is a fresh question. */}
        {open && target && kind && (
          <>
            <DialogHeader>
              <DialogTitle>
                {messages.label(HEADINGS[kind][target.mode === 'add' ? 0 : 1])}
              </DialogTitle>
            </DialogHeader>
            <ContentForm
              ref={form}
              target={target}
              onSubmit={content => {
                onSubmit(content);
                onClose();
              }}
            />
          </>
        )}
      </DialogContent>
    </Dialog>
  );
}

interface LinkDraft {
  label: string;
  href: string;
  description: string;
  /**
   * The link this row opened on, keys and all, and the words it was shown
   * in: carried with the row, so a row still gives back its own keys after
   * a row above it is removed.
   */
  was?: { label: Opened; description: Opened };
}

/** A label as the form opened on it: the key, and the words it showed. */
interface Opened {
  key: string;
  shown: string;
}

function opened(key: string, say: (value: string) => string): Opened {
  return { key, shown: say(key) };
}

/**
 * A form's starting values: the panel's own, or a new one's — empty, so what
 * the author types is all there is. The hint for a new note is the box's
 * placeholder, never text to delete first (the keyboard starts in the box).
 * The words are shown as `say` says them: the author's keys in words.
 */
function draftOf(target: ContentTarget, say: (value: string) => string) {
  const panel = target.mode === 'edit' ? target.panel : undefined;
  const links: LinkDraft[] =
    panel?.kind === 'links'
      ? panel.items.map(item => ({
          label: say(item.label),
          href: item.href,
          description: say(item.description ?? ''),
          was: {
            label: opened(item.label, say),
            description: opened(item.description ?? '', say),
          },
        }))
      : [{ label: '', href: '', description: '' }];
  return {
    title: say(panel?.title ?? ''),
    content: say(panel?.kind === 'markdown' ? panel.content : ''),
    src: panel?.kind === 'image' ? panel.src : '',
    alt: say(panel?.kind === 'image' ? (panel.alt ?? '') : ''),
    href: panel?.kind === 'image' ? (panel.href ?? '') : '',
    fit: panel?.kind === 'image' ? (panel.fit ?? 'contain') : 'contain',
    links,
  };
}

/**
 * What the panel held, keys and all, with the words the form opened on
 * them: what a box left as it opened gives back (`keptKey`) — the key,
 * even where the language changed while the form was open.
 */
function heldOf(target: ContentTarget, say: (value: string) => string) {
  const panel = target.mode === 'edit' ? target.panel : undefined;
  return {
    title: opened(panel?.title ?? '', say),
    content: opened(panel?.kind === 'markdown' ? panel.content : '', say),
    alt: opened(panel?.kind === 'image' ? (panel.alt ?? '') : '', say),
  };
}

const optional = (value: string) => value.trim() || undefined;

function ContentForm({
  ref,
  target,
  onSubmit,
}: {
  ref: Ref<HTMLFormElement>;
  target: ContentTarget;
  onSubmit: ContentEditorProps['onSubmit'];
}) {
  const messages = useViewMessages();
  const ids = useId();
  const kind = target.mode === 'add' ? target.kind : target.panel.kind;
  const [draft, setDraft] = useState(() => draftOf(target, messages.say));
  // What the panel held, keys and all: words left as they were shown go
  // back as the keys they were said from (`keptKey`).
  const [held] = useState(() => heldOf(target, messages.say));
  const back = (typed: string, was: Opened | undefined) =>
    was ? keptKey(typed, was.key, messages.say, was.shown) : typed;
  // Nothing is marked before the first try: an empty box a moment after it
  // appeared is not a mistake yet.
  const [tried, setTried] = useState(false);
  const set = (patch: Partial<typeof draft>) =>
    setDraft(current => ({ ...current, ...patch }));
  const setLink = (index: number, patch: Partial<LinkDraft>) =>
    set({
      links: draft.links.map((link, at) =>
        at === index ? { ...link, ...patch } : link,
      ),
    });

  const problems = {
    content: draft.content.trim() === '',
    src: !isSafeContentUrl(draft.src),
    href: draft.href.trim() !== '' && !isSafeContentUrl(draft.href),
    links: draft.links.map(link => ({
      label: link.label.trim() === '',
      href: !isSafeContentUrl(link.href),
    })),
  };
  const wrong =
    kind === 'markdown'
      ? problems.content
      : kind === 'image'
        ? problems.src || problems.href
        : problems.links.some(link => link.label || link.href);

  const submit = () => {
    setTried(true);
    if (wrong) return;
    const title = optional(back(draft.title, held.title));
    if (kind === 'markdown')
      onSubmit({ kind, title, content: back(draft.content, held.content) });
    else if (kind === 'image')
      onSubmit({
        kind,
        title,
        src: draft.src.trim(),
        alt: optional(back(draft.alt, held.alt)),
        href: optional(draft.href),
        fit: draft.fit,
      });
    else
      onSubmit({
        kind,
        title,
        items: draft.links.map(link => ({
          label: back(link.label.trim(), link.was?.label),
          href: link.href.trim(),
          description: optional(back(link.description, link.was?.description)),
        })),
      });
  };

  /** One labelled text box, marked once the form has been tried. */
  const text = (
    key: string,
    label: MessageKey,
    value: string,
    change: (value: string) => void,
    options: { invalid?: MessageKey | false; hint?: ReactNode } = {},
  ) => {
    const invalid = tried && options.invalid ? options.invalid : undefined;
    const id = `${ids}-${key}`;
    return (
      <Field data-invalid={invalid !== undefined || undefined}>
        <FieldLabel htmlFor={id}>{messages.label(label)}</FieldLabel>
        <Input
          id={id}
          value={value}
          aria-invalid={invalid !== undefined}
          onChange={event => change(event.target.value)}
        />
        {options.hint && <FieldDescription>{options.hint}</FieldDescription>}
        {invalid && <FieldError>{messages.label(invalid)}</FieldError>}
      </Field>
    );
  };
  const unsafe = 'dashboard.url.unsupported-scheme' as const;

  return (
    <form
      ref={ref}
      noValidate
      onSubmit={event => {
        event.preventDefault();
        submit();
      }}
      className="fve:flex fve:min-h-0 fve:flex-col fve:gap-4"
    >
      <FieldGroup className="fve:max-h-[min(28rem,60vh)] fve:overflow-y-auto fve:p-0.5">
        {kind === 'markdown' && (
          <Field data-invalid={(tried && problems.content) || undefined}>
            <FieldLabel htmlFor={`${ids}-content`}>
              {messages.label('label.content.markdown.field')}
            </FieldLabel>
            <Textarea
              id={`${ids}-content`}
              data-slot="content-markdown"
              rows={8}
              maxLength={MAX_MARKDOWN_LENGTH}
              value={draft.content}
              placeholder={messages.label('label.content.markdown.placeholder')}
              aria-invalid={tried && problems.content}
              onChange={event => set({ content: event.target.value })}
            />
            <FieldDescription>
              {messages.label('label.content.markdown.hint')}
            </FieldDescription>
            {tried && problems.content && (
              <FieldError>
                {messages.label('label.content.required')}
              </FieldError>
            )}
          </Field>
        )}
        {kind === 'image' && (
          <>
            {text(
              'src',
              'label.content.image.src',
              draft.src,
              src => set({ src }),
              {
                invalid:
                  draft.src.trim() === ''
                    ? 'label.content.required'
                    : problems.src && unsafe,
              },
            )}
            {text(
              'alt',
              'label.content.image.alt',
              draft.alt,
              alt => set({ alt }),
              {
                hint: messages.label('label.content.image.alt-hint'),
              },
            )}
            {text(
              'href',
              'label.content.image.href',
              draft.href,
              href => set({ href }),
              { invalid: problems.href && unsafe },
            )}
            <Field>
              <FieldLabel id={`${ids}-fit`}>
                {messages.label('label.content.image.fit')}
              </FieldLabel>
              <ToggleGroup
                value={[draft.fit]}
                onValueChange={value => {
                  if (value[0] === 'contain' || value[0] === 'cover')
                    set({ fit: value[0] });
                }}
                variant="outline"
                size="sm"
                spacing={0}
                aria-labelledby={`${ids}-fit`}
              >
                <ToggleGroupItem value="contain">
                  {messages.label('label.content.image.fit.contain')}
                </ToggleGroupItem>
                <ToggleGroupItem value="cover">
                  {messages.label('label.content.image.fit.cover')}
                </ToggleGroupItem>
              </ToggleGroup>
            </Field>
          </>
        )}
        {kind === 'links' &&
          draft.links.map((link, index) => (
            <FieldSet key={index} data-slot="content-link">
              <div className="fve:flex fve:items-center fve:justify-between fve:gap-2">
                <FieldLegend variant="label">
                  {messages.label('label.content.links.item', { n: index + 1 })}
                </FieldLegend>
                {draft.links.length > 1 && (
                  <IconButton
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    label={messages.label('label.content.links.drop', {
                      n: index + 1,
                    })}
                    onClick={() =>
                      set({
                        links: draft.links.filter((_, at) => at !== index),
                      })
                    }
                  >
                    <Trash2Icon />
                  </IconButton>
                )}
              </div>
              {text(
                `label-${index}`,
                'label.content.links.label',
                link.label,
                label => setLink(index, { label }),
                {
                  invalid:
                    problems.links[index].label && 'dashboard.link.label-empty',
                },
              )}
              {text(
                `href-${index}`,
                'label.content.links.href',
                link.href,
                href => setLink(index, { href }),
                {
                  invalid:
                    link.href.trim() === ''
                      ? 'label.content.required'
                      : problems.links[index].href && unsafe,
                },
              )}
              {text(
                `description-${index}`,
                'label.content.links.description',
                link.description,
                description => setLink(index, { description }),
              )}
            </FieldSet>
          ))}
        {kind === 'links' && draft.links.length < MAX_PANEL_LINKS && (
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="fve:self-start"
            onClick={() =>
              set({
                links: [
                  ...draft.links,
                  { label: '', href: '', description: '' },
                ],
              })
            }
          >
            <PlusIcon data-icon="inline-start" />
            {messages.label('label.content.links.more')}
          </Button>
        )}
        {text('title', 'label.content.title', draft.title, title =>
          set({ title }),
        )}
      </FieldGroup>
      <DialogFooter>
        <DialogClose render={<Button type="button" variant="outline" />}>
          {messages.label('label.dialog.cancel')}
        </DialogClose>
        <Button type="submit" data-slot="content-submit">
          {messages.label(
            target.mode === 'add'
              ? 'label.content.submit-add'
              : 'label.content.submit-edit',
          )}
        </Button>
      </DialogFooter>
    </form>
  );
}
