// Graphics quality levels a player (or the automatic) can select. Single source of truth for the
// settings schema and the 3D quality presets.
export const GRAPHICS_LEVELS = Object.freeze(['low', 'medium', 'high']);

// The level "Automatic" starts at (first start, and every time "Automatic" is selected anew, rule 4):
// the safest one. The automatic works its way up from here while the device has room to spare.
export const AUTO_START_LEVEL = 'low';
