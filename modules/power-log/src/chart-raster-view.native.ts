import { requireNativeViewManager } from 'expo-modules-core';
import type { ChartRasterProps } from './chart-raster-view.types';

export const ChartRasterView = requireNativeViewManager<ChartRasterProps>('PowerLog');
