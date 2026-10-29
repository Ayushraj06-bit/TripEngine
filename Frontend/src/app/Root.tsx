import { Outlet } from 'react-router';
import { LiveConnection } from '@/realtime/LiveConnection';
import { Toaster } from '@/ui/Toast';

export function Root() {
  return (
    <>
      <Outlet />
      <LiveConnection />
      <Toaster />
    </>
  );
}
