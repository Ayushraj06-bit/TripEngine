import { createBrowserRouter } from 'react-router';
import { Root } from './Root';
import { RouteError } from './RouteError';

/** Every screen is its own chunk, loaded when it is first visited. */
export const routes = [
  {
    path: '/',
    Component: Root,
    errorElement: <RouteError />,
    // While the first screen's chunk arrives: the plain paper the screen will sit on.
    hydrateFallbackElement: <div className="min-h-dvh bg-paper" />,
    children: [
      {
        index: true,
        errorElement: <RouteError />,
        lazy: async () => ({ Component: (await import('@/routes/Start')).Start }),
      },
      {
        path: 'design-system',
        errorElement: <RouteError />,
        lazy: async () => ({ Component: (await import('@/routes/DesignSystem')).DesignSystem }),
      },
      {
        path: 'design-system/map',
        errorElement: <RouteError />,
        lazy: async () => ({
          Component: (await import('@/routes/DesignSystemMap')).DesignSystemMap,
        }),
      },
    ],
  },
];

export const router = createBrowserRouter(routes);
