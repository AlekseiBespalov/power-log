import type { ViewProps } from 'react-native';

export interface ChartRasterStatus {
  status: 'ready' | 'error';
  key: string;
  sourceId?: string;
  viewId?: string;
  acceptanceId?: string;
  acceptanceGeneration?: number;
  width?: number;
  height?: number;
  displayScale?: number;
  message?: string;
}

export type ChartRasterProps = ViewProps & {
  sourceId: string;
  sceneKey: string;
  scene: string;
  selection: string;
  presentation?: number[];
  selectionTarget?: string;
  onRenderStatus?: (event: { nativeEvent: ChartRasterStatus }) => void;
};
