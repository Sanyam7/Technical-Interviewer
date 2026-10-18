import { useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';

import { cx } from '../../lib/cx';

export interface TabItem {
  id: string;
  label: string;
  /** Shown as a small number next to the label. */
  count?: number;
  panel: ReactNode;
}

interface TabsProps {
  label: string;
  tabs: TabItem[];
  className?: string;
}

/** WAI-ARIA tabs: one tab stop, arrow keys move between tabs, Home and End jump to the ends. */
export function Tabs({ label, tabs, className }: TabsProps) {
  const base = useId();
  const [activeId, setActiveId] = useState(tabs[0]?.id);
  const refs = useRef(new Map<string, HTMLButtonElement>());
  const active = tabs.find((tab) => tab.id === activeId) ?? tabs[0];

  function move(index: number) {
    const target = tabs[(index + tabs.length) % tabs.length];
    if (target) {
      setActiveId(target.id);
      refs.current.get(target.id)?.focus();
    }
  }

  function onKeyDown(event: KeyboardEvent, index: number) {
    switch (event.key) {
      case 'ArrowRight':
        move(index + 1);
        break;
      case 'ArrowLeft':
        move(index - 1);
        break;
      case 'Home':
        move(0);
        break;
      case 'End':
        move(tabs.length - 1);
        break;
      default:
        return;
    }
    event.preventDefault();
  }

  if (!active) {
    return null;
  }

  return (
    <div className={className}>
      <div
        role="tablist"
        aria-label={label}
        className="border-line -mx-1 flex gap-1 overflow-x-auto border-b px-1"
      >
        {tabs.map((tab, index) => {
          const selected = tab.id === active.id;
          return (
            <button
              key={tab.id}
              ref={(node) => {
                if (node) {
                  refs.current.set(tab.id, node);
                } else {
                  refs.current.delete(tab.id);
                }
              }}
              type="button"
              role="tab"
              id={`${base}-tab-${tab.id}`}
              aria-selected={selected}
              aria-controls={`${base}-panel-${tab.id}`}
              tabIndex={selected ? 0 : -1}
              onClick={() => {
                setActiveId(tab.id);
              }}
              onKeyDown={(event) => {
                onKeyDown(event, index);
              }}
              className={cx(
                '-mb-px inline-flex min-h-11 items-center gap-2 border-b-2 px-3 text-sm font-semibold whitespace-nowrap transition-colors',
                selected
                  ? 'border-brand text-ink'
                  : 'text-ink-muted hover:text-ink border-transparent',
              )}
            >
              {tab.label}
              {tab.count !== undefined ? (
                <span className="bg-surface-muted text-ink-muted rounded-full px-2 py-0.5 text-xs tabular-nums">
                  {tab.count}
                </span>
              ) : null}
            </button>
          );
        })}
      </div>
      <div
        role="tabpanel"
        id={`${base}-panel-${active.id}`}
        aria-labelledby={`${base}-tab-${active.id}`}
        tabIndex={0}
        className="pt-5"
      >
        {active.panel}
      </div>
    </div>
  );
}
