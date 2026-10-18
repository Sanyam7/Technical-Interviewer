import { useEffect, useId, useRef, type ReactNode } from 'react';

interface DialogProps {
  open: boolean;
  onClose: () => void;
  title: string;
  description?: string;
  children?: ReactNode;
  /** Buttons; the last one is usually the primary action. */
  footer: ReactNode;
}

/**
 * A modal on the browser's own <dialog>: it traps focus, closes on Escape and restores focus to the opener, which a
 * hand-made overlay has to imitate.
 */
export function Dialog({ open, onClose, title, description, children, footer }: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const descriptionId = useId();

  useEffect(() => {
    const dialog = ref.current;
    if (dialog === null) {
      return;
    }
    if (open && !dialog.open) {
      dialog.showModal();
    } else if (!open && dialog.open) {
      dialog.close();
    }
  }, [open]);

  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      aria-describedby={description ? descriptionId : undefined}
      onClose={onClose}
      onClick={(event) => {
        // A click on the backdrop lands on the <dialog> itself, not on its content.
        if (event.target === event.currentTarget) {
          onClose();
        }
      }}
      className="bg-surface text-ink border-line m-auto w-[min(32rem,calc(100vw-2rem))] rounded-2xl border p-0 shadow-2xl backdrop:bg-slate-950/55 backdrop:backdrop-blur-sm"
    >
      {open ? (
        <div className="p-6">
          <h2 id={titleId} className="text-xl font-semibold tracking-tight">
            {title}
          </h2>
          {description ? (
            <p id={descriptionId} className="text-ink-muted mt-2 text-sm">
              {description}
            </p>
          ) : null}
          {children ? <div className="mt-4">{children}</div> : null}
          <div className="mt-6 flex flex-wrap justify-end gap-3">{footer}</div>
        </div>
      ) : null}
    </dialog>
  );
}
