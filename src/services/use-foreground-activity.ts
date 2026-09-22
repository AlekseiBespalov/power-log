import { useFocusEffect } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { appIsForeground, subscribeAppVisibility } from './app-visibility';

export function useForegroundActivity() {
  const [focused, setFocused] = useState(false);
  const [foreground, setForeground] = useState(appIsForeground);
  useFocusEffect(useCallback(() => { setFocused(true); return () => setFocused(false); }, []));
  useEffect(() => subscribeAppVisibility(setForeground), []);
  return focused && foreground;
}
