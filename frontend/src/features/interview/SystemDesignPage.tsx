import { useParams } from 'react-router';

import { InterviewRoom } from './InterviewRoom';

/** The system design section of an interview. The room sends the browser here when the open question is a design question. */
export function SystemDesignPage() {
  const { id = '' } = useParams();
  return <InterviewRoom interviewId={id} />;
}
