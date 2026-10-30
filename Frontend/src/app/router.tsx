import { Navigate, type RouteObject } from 'react-router';
import { PublicOnly, RequireRole } from '@/auth/guards';
import { Root } from './Root';
import { RouteError } from './RouteError';

/**
 * Every screen is its own chunk, loaded when it is first visited, with an error boundary of its
 * own so one broken screen leaves the rest of the app working. The guards decide who sees what:
 * the public pages for the signed-out, and each role's area for that role only.
 */
export const routes: RouteObject[] = [
  {
    path: '/',
    Component: Root,
    errorElement: <RouteError />,
    // While the first screen's chunk arrives: the dark canvas the welcome screen sits on.
    hydrateFallbackElement: <div className="min-h-dvh bg-ink" />,
    children: [
      {
        Component: PublicOnly,
        errorElement: <RouteError />,
        children: [
          {
            path: 'welcome',
            lazy: async () => ({ Component: (await import('@/screens/Welcome')).Welcome }),
          },
          {
            path: 'sign-in',
            lazy: async () => ({ Component: (await import('@/screens/SignIn')).SignIn }),
          },
          {
            path: 'sign-up',
            lazy: async () => ({ Component: (await import('@/screens/SignUp')).SignUp }),
          },
        ],
      },
      {
        element: <RequireRole only="RIDER" />,
        errorElement: <RouteError />,
        children: [
          {
            index: true,
            lazy: async () => ({
              Component: (await import('@/screens/rider/RiderHome')).RiderHome,
            }),
          },
          {
            path: 'trip/:tripId',
            lazy: async () => ({
              Component: (await import('@/screens/TripStatus')).TripStatus,
            }),
          },
        ],
      },
      {
        path: 'driver',
        element: <RequireRole only="DRIVER" />,
        errorElement: <RouteError />,
        children: [
          {
            // Around every driver screen: the driver's position, shared while online or on a trip.
            lazy: async () => ({
              Component: (await import('@/screens/driver/DriverPresence')).DriverPresence,
            }),
            children: [
              {
                index: true,
                lazy: async () => ({
                  Component: (await import('@/screens/driver/DriverHome')).DriverHome,
                }),
              },
              {
                path: 'vehicle',
                lazy: async () => ({
                  Component: (await import('@/screens/driver/AddVehicle')).AddVehicle,
                }),
              },
              {
                path: 'approval',
                lazy: async () => ({
                  Component: (await import('@/screens/driver/Approval')).Approval,
                }),
              },
              {
                path: 'trip/:tripId',
                lazy: async () => ({
                  Component: (await import('@/screens/driver/DriverTrip')).DriverTrip,
                }),
              },
            ],
          },
        ],
      },
      {
        path: 'admin',
        element: <RequireRole only="ADMIN" />,
        errorElement: <RouteError />,
        children: [
          {
            index: true,
            lazy: async () => ({
              Component: (await import('@/screens/AdminNotice')).AdminNotice,
            }),
          },
        ],
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
      { path: '*', element: <Navigate to="/" replace /> },
    ],
  },
];
