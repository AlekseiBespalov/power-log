import type { ReactNode } from 'react';

export const Icon = () => null;
export const ModalDialog = ({ visible, children }: { visible: boolean; children: ReactNode }) =>
  visible ? children : null;
export const useFocusedModal = (visible: boolean) => visible;
