import type { SVGProps } from 'react';

const PATHS = {
  'arrow-right': 'M5 12h14M13 6l6 6-6 6',
  'arrow-left': 'M19 12H5M11 6l-6 6 6 6',
  'arrow-up-right': 'M7 17 17 7M8 7h9v9',
  check: 'M5 12.5 10 17.5 19 7',
  x: 'M6 6l12 12M18 6 6 18',
  clock: 'M12 7v5l3 2M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
  code: 'm8 8-4 4 4 4M16 8l4 4-4 4M14 5l-4 14',
  layers: 'm12 3 9 5-9 5-9-5 9-5ZM3 13l9 5 9-5M3 17.5l9 5 9-5',
  message: 'M4 5h16v11H9l-5 4V5Z',
  chart: 'M4 20V10M10 20V4M16 20v-7M22 20H2',
  user: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM4 21c0-4 3.6-6 8-6s8 2 8 6',
  history: 'M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5M12 8v4l3 2',
  home: 'M4 11 12 4l8 7v9h-5v-6H9v6H4v-9Z',
  plus: 'M12 5v14M5 12h14',
  alert: 'M12 4 2.5 20h19L12 4ZM12 10v4.5M12 17.5v.01',
  refresh: 'M20 11a8 8 0 0 0-14.5-3.5L4 9M4 4v5h5M4 13a8 8 0 0 0 14.5 3.5L20 15M20 20v-5h-5',
  sparkle:
    'M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3ZM19 16l.8 2.2L22 19l-2.2.8L19 22l-.8-2.2L16 19l2.2-.8L19 16Z',
  lock: 'M6 11V8a6 6 0 0 1 12 0v3M5 11h14v10H5V11Z',
  target:
    'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM12 16.5a4.5 4.5 0 1 0 0-9 4.5 4.5 0 0 0 0 9ZM12 12h.01',
  book: 'M4 5a2 2 0 0 1 2-2h13v16H6a2 2 0 0 0-2 2V5ZM4 21a2 2 0 0 1 2-2h13',
  logout: 'M10 4H5v16h5M16 8l4 4-4 4M20 12H9',
  menu: 'M4 7h16M4 12h16M4 17h16',
  play: 'M7 4.5v15l12-7.5-12-7.5Z',
  flag: 'M5 21V4M5 4h13l-2.5 4.5L18 13H5',
  shield: 'M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6l-8-3ZM9 12l2.2 2.2L15.5 10',
  trend: 'M3 17l6-6 4 4 8-8M15 7h6v6',
  gap: 'M4 6h16M4 12h6M14 12h6M4 18h16',
  eye: 'M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12ZM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z',
  copy: 'M9 9h11v11H9V9ZM5 15V4h11',
  upload: 'M12 16V4M7 9l5-5 5 5M4 15v5h16v-5',
  file: 'M6 3h8l4 4v14H6V3ZM14 3v4h4M9 12h6M9 16h6',
  trash: 'M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3',
} as const;

export type IconName = keyof typeof PATHS;

interface IconProps extends Omit<SVGProps<SVGSVGElement>, 'name'> {
  name: IconName;
}

/** Decorative stroke icon; the label next to it carries the meaning. */
export function Icon({ name, className = 'size-5', ...rest }: IconProps) {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      {...rest}
    >
      <path d={PATHS[name]} />
    </svg>
  );
}
