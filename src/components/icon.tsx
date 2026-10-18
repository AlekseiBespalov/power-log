import Svg, { Circle, Path, Rect } from 'react-native-svg';
import { colors } from './ui';

export type IconName =
  'bike' | 'watch' | 'settings' | 'layout' | 'history' | 'chevron' | 'route' | 'play' | 'pause' | 'lap' | 'stop';
export function Icon({ name, color = colors.muted, size = 20 }: { name: IconName; color?: string; size?: number }) {
  return (
    <Svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke={color}
      strokeWidth={1.7}
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {name === 'bike' && (
        <>
          <Circle cx={4.8} cy={17.2} r={3.8} />
          <Circle cx={19.2} cy={17.2} r={3.8} />
          <Path d="m4.8 17.2 4-5.5 7.6-1.8-4.9 7.3H4.8m6.7 0L8 9.5M6.5 9.5h3m9.7 7.7L15.6 7h2.8" />
          <Path d="m12.5 15.7 2.7-4" strokeWidth={2.8} />
          <Path d="m11.3 1.5-3 4h2.2L9.4 8.2l4.3-4.4h-2.4l1.1-2.3Z" fill={color} stroke="none" />
        </>
      )}
      {name === 'watch' && (
        <>
          <Rect x={6} y={6} width={12} height={12} rx={4} />
          <Path d="M9 6V2h6v4M9 18v4h6v-4m-3-9v3l2 1" />
        </>
      )}
      {name === 'settings' && (
        <>
          <Path d="M4 7h16M4 17h16" />
          <Circle cx={9} cy={7} r={3} fill={colors.surface} />
          <Circle cx={15} cy={17} r={3} fill={colors.surface} />
        </>
      )}
      {name === 'route' && (
        <>
          <Circle cx={6} cy={5} r={2} />
          <Circle cx={18} cy={19} r={2} />
          <Path d="M8 5h7a4 4 0 010 8H9a3 3 0 000 6h7" />
        </>
      )}
      {name === 'play' && <Path d="M8 5v14l11-7z" fill={color} stroke="none" />}
      {name === 'pause' && <Path d="M7 5h4v14H7zM13 5h4v14h-4z" fill={color} stroke="none" />}
      {name === 'lap' && <Path d="M6 21V4m0 0h11l-2.5 4L17 12H6" />}
      {name === 'stop' && <Rect x={6} y={6} width={12} height={12} rx={2} fill={color} stroke="none" />}
      {name === 'history' && (
        <>
          <Circle cx={12} cy={12} r={8.5} />
          <Path d="M12 7.5V12l3 2" />
        </>
      )}
      {name === 'layout' && (
        <>
          <Rect x={3.5} y={4} width={17} height={16} rx={2} />
          <Path d="M3.5 10h17M10 10v10" />
        </>
      )}
      {name === 'chevron' && <Path d="M6 9l6 6 6-6" />}
    </Svg>
  );
}
