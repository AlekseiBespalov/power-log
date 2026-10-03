import type { ExportPlatform, ExportSource, OpenExportSink } from '../core/export/types';
import { openBrowserExportSink } from './browser-export-sink';
import { browserExportSource } from './browser-export-source';

export const exportSource = (): ExportSource => browserExportSource;

export const openExportSink: OpenExportSink = openBrowserExportSink;

export const exportPlatform = (): ExportPlatform => 'web';
