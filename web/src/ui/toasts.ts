import { create } from 'zustand';

export type ToastTone = 'info' | 'success' | 'error';

export interface ToastItem {
  id: number;
  message: string;
  tone: ToastTone;
  action?: { label: string; onClick: () => void };
}

/** Long enough to read a sentence twice; errors stay longer. */
export const TOAST_MS: Record<ToastTone, number> = { info: 5000, success: 4000, error: 8000 };

interface ToastState {
  toasts: ToastItem[];
  show: (toast: Omit<ToastItem, 'id'>) => number;
  dismiss: (id: number) => void;
}

let next = 1;

export const useToasts = create<ToastState>()((set, get) => ({
  toasts: [],
  show: (toast) => {
    const id = next++;
    // Three at most: a pile of toasts over the map is noise.
    set({ toasts: [...get().toasts, { ...toast, id }].slice(-3) });
    setTimeout(() => {
      get().dismiss(id);
    }, TOAST_MS[toast.tone]);
    return id;
  },
  dismiss: (id) => {
    set({ toasts: get().toasts.filter((toast) => toast.id !== id) });
  },
}));

/** `toast('Wallet topped up', 'success')` from anywhere, components or not. */
export function toast(message: string, tone: ToastTone = 'info', action?: ToastItem['action']) {
  return useToasts.getState().show(action ? { message, tone, action } : { message, tone });
}
