/** Opens the timeline entry of a question and scrolls to it. A real link, so it also works as a plain anchor. */
export function jumpToTurn(questionId: string): void {
  const entry = document.getElementById(`turn-${questionId}`);
  if (!entry) {
    return;
  }
  entry.querySelector('details')?.setAttribute('open', '');
  const smooth =
    typeof window.matchMedia !== 'function' ||
    !window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  entry.scrollIntoView({ behavior: smooth ? 'smooth' : 'auto', block: 'start' });
  entry.focus({ preventScroll: true });
}
