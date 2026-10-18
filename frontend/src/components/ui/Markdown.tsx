import ReactMarkdown, { type Components } from 'react-markdown';

import { cx } from '../../lib/cx';

/**
 * Model and candidate text may contain Markdown (backticks, lists). It is rendered without raw HTML and without links or
 * images, so nothing in it can load a resource or navigate (ARCHITECTURE.md section 6).
 */
const ALLOWED = [
  'p',
  'strong',
  'em',
  'code',
  'pre',
  'ul',
  'ol',
  'li',
  'blockquote',
  'br',
  'h1',
  'h2',
  'h3',
  'h4',
];

const COMPONENTS: Components = {
  h1: ({ children }) => <p className="font-semibold">{children}</p>,
  h2: ({ children }) => <p className="font-semibold">{children}</p>,
  h3: ({ children }) => <p className="font-semibold">{children}</p>,
  h4: ({ children }) => <p className="font-semibold">{children}</p>,
  ul: ({ children }) => <ul className="list-disc space-y-1 pl-5">{children}</ul>,
  ol: ({ children }) => <ol className="list-decimal space-y-1 pl-5">{children}</ol>,
  blockquote: ({ children }) => (
    <blockquote className="border-line text-ink-muted border-l-2 pl-3">{children}</blockquote>
  ),
  pre: ({ children }) => (
    <pre className="border-line bg-surface-muted overflow-x-auto rounded-lg border p-3 font-mono text-[0.8125rem] leading-relaxed [&_code]:bg-transparent [&_code]:p-0">
      {children}
    </pre>
  ),
  code: ({ className, children }) =>
    className ? (
      <code className={className}>{children}</code>
    ) : (
      <code className="bg-surface-muted rounded px-1.5 py-0.5 font-mono text-[0.85em]">
        {children}
      </code>
    ),
};

export function Markdown({ children, className }: { children: string; className?: string }) {
  return (
    <div className={cx('space-y-3 leading-relaxed', className)}>
      <ReactMarkdown allowedElements={ALLOWED} unwrapDisallowed skipHtml components={COMPONENTS}>
        {children}
      </ReactMarkdown>
    </div>
  );
}
