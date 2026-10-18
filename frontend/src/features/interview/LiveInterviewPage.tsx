import { useParams } from 'react-router';

import { InterviewRoom } from './InterviewRoom';

/** The live interview: introduction, technical questions and their follow-ups. */
export function LiveInterviewPage() {
  const { id = '' } = useParams();
  return <InterviewRoom interviewId={id} />;
}
