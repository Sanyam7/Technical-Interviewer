import { go } from '@codemirror/lang-go';
import { java } from '@codemirror/lang-java';
import { javascript } from '@codemirror/lang-javascript';
import { python } from '@codemirror/lang-python';
import type { Extension } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import CodeMirror from '@uiw/react-codemirror';
import { useMemo, useSyncExternalStore } from 'react';

interface CodeEditorProps {
  value: string;
  onChange: (value: string) => void;
  /** Catalog language code: java, javascript, typescript, python, go. */
  language: string | null;
  readOnly: boolean;
  onSubmit: () => void;
  ariaLabel: string;
}

function languageExtension(language: string | null): Extension[] {
  switch (language) {
    case 'java':
      return [java()];
    case 'javascript':
      return [javascript()];
    case 'python':
      return [python()];
    case 'typescript':
      return [javascript({ typescript: true })];
    case 'go':
      return [go()];
    default:
      return [];
  }
}

const DARK = '(prefers-color-scheme: dark)';

const BASIC_SETUP = { foldGutter: false, highlightActiveLine: false, autocompletion: false };

function subscribe(callback: () => void): () => void {
  const media = window.matchMedia(DARK);
  media.addEventListener('change', callback);
  return () => {
    media.removeEventListener('change', callback);
  };
}

/** The editor, loaded on demand (see CodingWorkspace): CodeMirror is the largest dependency and only coding uses it. */
export default function CodeEditor({
  value,
  onChange,
  language,
  readOnly,
  onSubmit,
  ariaLabel,
}: CodeEditorProps) {
  const dark = useSyncExternalStore(
    subscribe,
    () => window.matchMedia(DARK).matches,
    () => false,
  );
  // The editor reconfigures itself whenever these change identity (and re-parses the code with a new language instance):
  // built once per language, not on every keystroke.
  const extensions = useMemo(
    () => [
      ...languageExtension(language),
      // The editable element needs a name of its own; the wrapper's aria-label does not reach it.
      EditorView.contentAttributes.of({ 'aria-label': ariaLabel }),
    ],
    [language, ariaLabel],
  );

  return (
    <CodeMirror
      value={value}
      height="26rem"
      theme={dark ? 'dark' : 'light'}
      extensions={extensions}
      readOnly={readOnly}
      aria-label={ariaLabel}
      onChange={onChange}
      onKeyDown={(event) => {
        if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
          event.preventDefault();
          onSubmit();
        }
      }}
      basicSetup={BASIC_SETUP}
      className="overflow-hidden rounded-xl border border-[var(--tone-control-border)] text-[0.9rem]"
    />
  );
}
