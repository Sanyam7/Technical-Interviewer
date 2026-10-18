import { useState, type SyntheticEvent } from 'react';

import type { Architecture, ComponentKind } from '../../api/types';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { SelectField, TextField } from '../../components/ui/Field';
import { Icon } from '../../components/ui/Icon';
import {
  addComponent,
  addConnection,
  componentProblem,
  connectionProblem,
  KINDS,
  kindLabel,
  MAX_COMPONENTS,
  MAX_CONNECTIONS,
  MAX_LABEL,
  MAX_NAME,
  MAX_NOTES,
  removeComponent,
  removeConnection,
  STARTER,
} from './architecture';
import { ArchitectureDiagramView } from './ArchitectureDiagramView';

interface ArchitectureEditorProps {
  diagram: Architecture;
  onChange: (next: Architecture) => void;
  disabled?: boolean;
}

/**
 * Builds an architecture diagram with two small forms, one for components and one for connections, and draws it as it grows.
 * Forms instead of drag and drop: every action works with a keyboard and a screen reader, and the drawing lays itself out.
 */
export function ArchitectureEditor({
  diagram,
  onChange,
  disabled = false,
}: ArchitectureEditorProps) {
  const [kind, setKind] = useState<ComponentKind>('SERVICE');
  const [name, setName] = useState('');
  const [notes, setNotes] = useState('');
  const [componentError, setComponentError] = useState<string>();
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [label, setLabel] = useState('');
  const [async, setAsync] = useState(false);
  const [connectionError, setConnectionError] = useState<string>();
  const names = new Map(diagram.components.map((c) => [c.id, c.name]));

  function submitComponent(event: SyntheticEvent) {
    event.preventDefault();
    const problem = componentProblem(diagram, name);
    setComponentError(problem ?? undefined);
    if (problem) {
      return;
    }
    onChange(addComponent(diagram, kind, name, notes));
    setName('');
    setNotes('');
  }

  function submitConnection(event: SyntheticEvent) {
    event.preventDefault();
    const problem = connectionProblem(diagram, from, to);
    setConnectionError(problem ?? undefined);
    if (problem) {
      return;
    }
    onChange(addConnection(diagram, from, to, label, async));
    setLabel('');
    setAsync(false);
  }

  return (
    <div className="space-y-5">
      {diagram.components.length === 0 ? (
        <div className="border-line text-ink-muted rounded-xl border border-dashed p-5 text-sm">
          <p>
            No components yet. Add the parts of your system below, then connect them; the drawing
            updates as you go and is sent with your answer.
          </p>
          <Button
            variant="secondary"
            size="sm"
            className="mt-3"
            disabled={disabled}
            icon={<Icon name="layers" className="size-4" />}
            onClick={() => {
              onChange(STARTER);
            }}
          >
            Start from client, load balancer, service, database
          </Button>
        </div>
      ) : (
        <ArchitectureDiagramView diagram={diagram} />
      )}

      <form onSubmit={submitComponent} className="space-y-3" aria-label="Add a component">
        <p className="text-sm font-semibold">
          Add a component{' '}
          <span className="text-ink-muted font-normal">
            ({diagram.components.length} of {MAX_COMPONENTS})
          </span>
        </p>
        <div className="grid gap-3 sm:grid-cols-[minmax(0,10rem)_minmax(0,1fr)]">
          <SelectField
            label="Kind"
            value={kind}
            disabled={disabled}
            onChange={(event) => {
              setKind(event.target.value as ComponentKind);
            }}
          >
            {KINDS.map((entry) => (
              <option key={entry.kind} value={entry.kind}>
                {entry.label}
              </option>
            ))}
          </SelectField>
          <TextField
            label="Name"
            value={name}
            maxLength={MAX_NAME}
            disabled={disabled}
            placeholder="Orders database (PostgreSQL)"
            error={componentError}
            onChange={(event) => {
              setName(event.target.value);
            }}
          />
        </div>
        <TextField
          label="What it does or holds (optional)"
          value={notes}
          maxLength={MAX_NOTES}
          disabled={disabled}
          placeholder="orders and their state; primary with two replicas"
          onChange={(event) => {
            setNotes(event.target.value);
          }}
        />
        <Button
          type="submit"
          variant="secondary"
          size="sm"
          disabled={disabled}
          icon={<Icon name="plus" className="size-4" />}
        >
          Add component
        </Button>
      </form>

      {diagram.components.length > 0 ? (
        <ul className="divide-line border-line divide-y rounded-xl border" aria-label="Components">
          {diagram.components.map((component) => (
            <li key={component.id} className="flex items-start gap-3 px-3 py-2.5 text-sm">
              <Badge>{kindLabel(component.kind)}</Badge>
              <span className="min-w-0 flex-1">
                <span className="font-medium wrap-anywhere">{component.name}</span>
                {component.notes ? (
                  <span className="text-ink-muted block wrap-anywhere">{component.notes}</span>
                ) : null}
              </span>
              <Button
                variant="ghost"
                size="sm"
                disabled={disabled}
                aria-label={`Remove ${component.name}`}
                onClick={() => {
                  onChange(removeComponent(diagram, component.id));
                }}
              >
                <Icon name="x" className="size-4" />
              </Button>
            </li>
          ))}
        </ul>
      ) : null}

      {diagram.components.length >= 2 ? (
        <form onSubmit={submitConnection} className="space-y-3" aria-label="Add a connection">
          <p className="text-sm font-semibold">
            Connect two components{' '}
            <span className="text-ink-muted font-normal">
              ({diagram.connections.length} of {MAX_CONNECTIONS})
            </span>
          </p>
          <div className="grid gap-3 sm:grid-cols-2">
            <SelectField
              label="From"
              value={from}
              disabled={disabled}
              error={connectionError}
              onChange={(event) => {
                setFrom(event.target.value);
              }}
            >
              <option value="">Choose…</option>
              {diagram.components.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </SelectField>
            <SelectField
              label="To"
              value={to}
              disabled={disabled}
              onChange={(event) => {
                setTo(event.target.value);
              }}
            >
              <option value="">Choose…</option>
              {diagram.components.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </SelectField>
          </div>
          <TextField
            label="What flows (optional)"
            value={label}
            maxLength={MAX_LABEL}
            disabled={disabled}
            placeholder="publishes OrderPlaced"
            onChange={(event) => {
              setLabel(event.target.value);
            }}
          />
          <label className="flex min-h-11 items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={async}
              disabled={disabled}
              className="accent-brand size-4"
              onChange={(event) => {
                setAsync(event.target.checked);
              }}
            />
            Asynchronous (the sender does not wait: a message or an event)
          </label>
          <Button
            type="submit"
            variant="secondary"
            size="sm"
            disabled={disabled}
            icon={<Icon name="plus" className="size-4" />}
          >
            Add connection
          </Button>
        </form>
      ) : null}

      {diagram.connections.length > 0 ? (
        <ul className="divide-line border-line divide-y rounded-xl border" aria-label="Connections">
          {diagram.connections.map((connection, index) => {
            const text = `${names.get(connection.from) ?? connection.from} → ${names.get(connection.to) ?? connection.to}`;
            return (
              <li
                key={`${connection.from}>${connection.to}`}
                className="flex items-start gap-3 px-3 py-2.5 text-sm"
              >
                <span className="min-w-0 flex-1 wrap-anywhere">
                  <span className="font-medium">{text}</span>
                  {connection.async ? <Badge className="ml-2">async</Badge> : null}
                  {connection.label ? (
                    <span className="text-ink-muted block">{connection.label}</span>
                  ) : null}
                </span>
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={disabled}
                  aria-label={`Remove the connection ${text}`}
                  onClick={() => {
                    onChange(removeConnection(diagram, index));
                  }}
                >
                  <Icon name="x" className="size-4" />
                </Button>
              </li>
            );
          })}
        </ul>
      ) : null}
    </div>
  );
}
