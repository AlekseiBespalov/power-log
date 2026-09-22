import Svg, { Circle, Path, Rect } from 'react-native-svg';
import { colors } from './ui';

export type IconName = 'bike' | 'watch' | 'settings' | 'layout' | 'history' | 'chevron' | 'route' | 'play' | 'pause' | 'lap' | 'stop';
export function Icon({ name, color = colors.muted, size = 20 }: { name: IconName; color?: string; size?: number }) {
  return <Svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round">
    {name === 'bike' && <><Circle cx={5} cy={17} r={4} /><Circle cx={19} cy={17} r={4} /><Path d="M5 17l5-10 5 10H5m5-10h6l3 10M8 7h4m4 0V4h3" /></>}
    {name === 'watch' && <><Rect x={6} y={6} width={12} height={12} rx={4} /><Path d="M9 6V2h6v4M9 18v4h6v-4m-3-9v3l2 1" /></>}
    {name === 'settings' && <><Path d="M4 7h16M4 17h16" /><Circle cx={9} cy={7} r={3} fill={colors.surface} /><Circle cx={15} cy={17} r={3} fill={colors.surface} /></>}
    {name === 'route' && <><Circle cx={6} cy={5} r={2} /><Circle cx={18} cy={19} r={2} /><Path d="M8 5h7a4 4 0 010 8H9a3 3 0 000 6h7" /></>}
    {name === 'play' && <Path d="M8 5v14l11-7z" fill={color} stroke="none" />}
    {name === 'pause' && <Path d="M7 5h4v14H7zM13 5h4v14h-4z" fill={color} stroke="none" />}
    {name === 'lap' && <Path d="M6 21V4m0 0h11l-2.5 4L17 12H6" />}
    {name === 'stop' && <Rect x={6} y={6} width={12} height={12} rx={2} fill={color} stroke="none" />}
    {name === 'history' && <><Circle cx={12} cy={12} r={8.5} /><Path d="M12 7.5V12l3 2" /></>}
    {name === 'layout' && <><Rect x={3.5} y={4} width={17} height={16} rx={2} /><Path d="M3.5 10h17M10 10v10" /></>}
    {name === 'chevron' && <Path d="M6 9l6 6 6-6" />}
  </Svg>;
}
