import './styles/theme.css';
import { QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { queryClient } from './app/queryClient';
import { routes } from './app/router';
import { sendToSignIn } from './app/sessionEnded';
import { installAuth, restoreSession } from './auth/auth';

const router = createBrowserRouter(routes);

installAuth(() => {
  sendToSignIn(router, queryClient);
});

// Once, before the first screen: renew the session from its cookie, if there is one.
void restoreSession();

const root = document.getElementById('root');
if (!root) throw new Error('index.html has no #root');

createRoot(root).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);
