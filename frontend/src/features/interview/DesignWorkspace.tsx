import { useId } from 'react';

import { useTranscript } from '../../api/interviews';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { Card } from '../../components/ui/Card';
import { Icon } from '../../components/ui/Icon';
import { Tabs } from '../../components/ui/Tabs';
import { CRITERION_LABEL } from '../../lib/labels';
import { forAnswer } from '../design/architecture';
import { ArchitectureEditor } from '../design/ArchitectureEditor';
import { DESIGN_DIMENSIONS } from '../design/dimensions';
import { latestSubmitted, useDiagramDraft } from '../design/useDiagramDraft';
import { AnswerBar } from './AnswerBar';
import { EarlierTurns } from './EarlierTurns';
import { QuestionCard } from './QuestionCard';
import { ThinkingPanel } from './ThinkingPanel';
import { useAnswering } from './useAnswering';
import type { WorkspaceProps } from './workspace';

/** The usual shape of a design answer. A starting point you may insert, change or ignore. */
const OUTLINE = `## Requirements and scale
Functional:
Non-functional (latency, availability, consistency):
Estimates (requests per second, storage):

## API


## Data model and storage (SQL or NoSQL, and why)


## Architecture and request flow


## Caching


## Queues and asynchronous work


## Scaling


## Availability, reliability and failure handling


## Security and observability


## Trade-offs

`;

/**
 * System design: the problem with what the interviewer said up front, a structured written answer and an architecture diagram
 * built next to it. A follow-up probes one dimension of the design; the diagram carries over so the candidate can change it.
 */
export function DesignWorkspace({ session, question, fresh, commands }: WorkspaceProps) {
  const { data: transcript } = useTranscript(session.id);
  const [diagram, setDiagram] = useDiagramDraft(session.id, latestSubmitted(transcript?.entries));
  const answering = useAnswering({ session, question, commands, architecture: forAnswer(diagram) });
  const labelId = useId();
  const isBlank = answering.value.trim() === '';
  const design = question.design;
  const probe = design?.probe ?? null;

  const writing = (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <label htmlFor={labelId} className="text-sm font-semibold">
          {probe ? 'Your answer' : 'Your design'}
        </label>
        {!probe ? (
          <Button
            variant="secondary"
            size="sm"
            disabled={!isBlank || answering.submitting}
            icon={<Icon name="layers" className="size-4" />}
            onClick={() => {
              answering.setValue(OUTLINE);
            }}
          >
            Start from an outline
          </Button>
        ) : null}
      </div>
      <textarea
        id={labelId}
        value={answering.value}
        onChange={(event) => {
          answering.setValue(event.target.value);
        }}
        readOnly={answering.submitting}
        placeholder={
          probe
            ? 'Answer for your design: what happens, what you would change, and what it costs.'
            : 'Describe your design: requirements, API, data, components, how it scales, what can fail.'
        }
        className="border-control-border bg-page text-ink placeholder:text-ink-muted/70 focus-visible:border-brand min-h-[24rem] w-full resize-y rounded-xl border px-4 py-3 font-mono text-[0.9rem] leading-relaxed read-only:opacity-70"
      />
    </div>
  );

  return (
    <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,5fr)_minmax(0,7fr)]">
      <div className="space-y-5">
        <EarlierTurns interviewId={session.id} currentQuestionId={question.id} />
        <QuestionCard question={question} fresh={fresh} />
        {design && design.facts.length > 0 ? (
          <Card
            title="What the interviewer told you"
            description="State anything else you need as an assumption."
          >
            <ul className="list-disc space-y-1.5 pl-5 text-sm">
              {design.facts.map((fact) => (
                <li key={fact}>{fact}</li>
              ))}
            </ul>
          </Card>
        ) : null}
        <Card
          title="What a design is judged on"
          description="Every area is rated on the whole discussion; one never discussed rates 0. Ratings rest on your own words."
        >
          <ul className="grid gap-2 text-sm sm:grid-cols-2">
            {DESIGN_DIMENSIONS.map(({ criterion, hint }) => (
              <li key={criterion}>
                <span className="font-semibold">{CRITERION_LABEL[criterion]}</span>
                {probe === criterion ? (
                  <Badge tone="warning" className="ml-2">
                    asked now
                  </Badge>
                ) : null}
                <span className="text-ink-muted block">{hint}</span>
              </li>
            ))}
          </ul>
        </Card>
      </div>

      <div className="border-line bg-surface shadow-raised space-y-3 rounded-2xl border p-5 lg:sticky lg:top-24">
        <Tabs
          label="Answer and diagram"
          tabs={[
            { id: 'write', label: 'Write', panel: writing },
            {
              id: 'diagram',
              label: 'Architecture diagram',
              count: diagram.components.length,
              panel: (
                <ArchitectureEditor
                  diagram={diagram}
                  onChange={setDiagram}
                  disabled={answering.submitting}
                />
              ),
            },
          ]}
        />
        {answering.submitting ? (
          <ThinkingPanel title="The interviewer is reviewing your design" />
        ) : null}
        <AnswerBar
          length={answering.value.length}
          limit={answering.limit}
          canSubmit={answering.canSubmit}
          submitting={answering.submitting}
          skipping={answering.skipping}
          error={answering.error}
          onSubmit={answering.submit}
          onRetry={answering.retry}
          onSkip={answering.skip}
          isFollowUp={question.isFollowUp}
          hint={
            diagram.components.length > 0
              ? `Your diagram (${diagram.components.length} components) is sent with the answer. Drafts are saved in this browser.`
              : 'Plain text and Markdown are fine; add a diagram in the second tab. Drafts are saved in this browser.'
          }
        />
      </div>
    </div>
  );
}
