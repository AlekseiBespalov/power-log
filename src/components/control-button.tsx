import type { ReactNode } from 'react';
import type { ControlState } from './screen-model';
import { Button } from './ui';

export function ControlButton({
  control,
  onPress,
  secondary,
  danger,
  block,
  icon,
  testID,
}: {
  control: ControlState;
  onPress: () => void;
  secondary?: boolean;
  danger?: boolean;
  block?: boolean;
  icon?: ReactNode;
  testID?: string;
}) {
  return (
    <Button
      testID={testID}
      secondary={secondary}
      danger={danger}
      block={block}
      icon={icon}
      disabled={!control.enabled || control.busy}
      accessibilityHint={control.reason ?? undefined}
      onPress={onPress}
    >
      {control.label}
    </Button>
  );
}
