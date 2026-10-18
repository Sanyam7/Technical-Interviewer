import { createBrowserRouter, type RouteObject } from 'react-router';

import { GuestOnly, RequireAuth } from '../features/auth/RouteGuards';
import { AppShell } from './layout/AppShell';
import { FocusLayout } from './layout/FocusLayout';
import { PublicLayout } from './layout/PublicLayout';
import { NotFoundPage } from './NotFoundPage';
import { RouteFallback } from './RouteFallback';

/**
 * Every page but the entry ones is loaded on demand, so the first visit does not download the report, the dashboard charts
 * or the editor. `lazy` resolves the module before the route renders.
 */
export const routes: RouteObject[] = [
  {
    element: <PublicLayout />,
    HydrateFallback: RouteFallback,
    children: [
      {
        index: true,
        lazy: async () => ({
          Component: (await import('../features/landing/LandingPage')).LandingPage,
        }),
      },
      {
        path: 'status',
        lazy: async () => ({
          Component: (await import('../features/status/StatusPage')).StatusPage,
        }),
      },
      {
        element: <GuestOnly />,
        children: [
          {
            path: 'login',
            lazy: async () => ({
              Component: (await import('../features/auth/LoginPage')).LoginPage,
            }),
          },
          {
            path: 'register',
            lazy: async () => ({
              Component: (await import('../features/auth/RegisterPage')).RegisterPage,
            }),
          },
        ],
      },
    ],
  },
  {
    element: <RequireAuth />,
    HydrateFallback: RouteFallback,
    children: [
      {
        element: <AppShell />,
        children: [
          {
            path: 'dashboard',
            lazy: async () => ({
              Component: (await import('../features/dashboard/DashboardPage')).DashboardPage,
            }),
          },
          {
            path: 'interviews',
            lazy: async () => ({
              Component: (await import('../features/history/HistoryPage')).HistoryPage,
            }),
          },
          {
            path: 'interviews/new',
            lazy: async () => ({
              Component: (await import('../features/setup/SetupPage')).SetupPage,
            }),
          },
          {
            path: 'interviews/:id/results',
            lazy: async () => ({
              Component: (await import('../features/results/ResultsPage')).ResultsPage,
            }),
          },
          {
            path: 'profile',
            lazy: async () => ({
              Component: (await import('../features/profile/ProfilePage')).ProfilePage,
            }),
          },
        ],
      },
      {
        element: <FocusLayout />,
        children: [
          {
            path: 'interviews/:id/live',
            lazy: async () => ({
              Component: (await import('../features/interview/LiveInterviewPage'))
                .LiveInterviewPage,
            }),
          },
          {
            path: 'interviews/:id/coding',
            lazy: async () => ({
              Component: (await import('../features/interview/CodingInterviewPage'))
                .CodingInterviewPage,
            }),
          },
          {
            path: 'interviews/:id/design',
            lazy: async () => ({
              Component: (await import('../features/interview/SystemDesignPage')).SystemDesignPage,
            }),
          },
        ],
      },
    ],
  },
  {
    element: <PublicLayout />,
    HydrateFallback: RouteFallback,
    children: [{ path: '*', element: <NotFoundPage /> }],
  },
];

export function createAppRouter() {
  return createBrowserRouter(routes);
}
