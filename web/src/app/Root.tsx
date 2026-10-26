import { Outlet } from 'react-router';
import { Toaster } from '@/ui/Toast';

export function Root() {
  return (
    <>
      <Outlet />
      <Toaster />
    </>
  );
}
