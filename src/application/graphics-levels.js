// Graphics quality levels a player (or the automatic) can select. Single source of truth for the
// settings schema and the 3D quality presets.
export const GRAPHICS_LEVELS = Object.freeze(['low', 'medium', 'high']);

// The level "Automatic" starts at (first start, and every time "Automatic" is selected anew, rule 4):
// the safest one. The automatic works its way up from here while the device has room to spare.
export const AUTO_START_LEVEL = 'low';

// Seconds after a page went to the background or came back to the foreground in which a loss of
// the 3D picture says nothing about the device (technical value, no game play): Android browsers
// often drop the context or reload the tab on an app switch, and right after the return the page
// is still waking up. Used by the context-loss rule (quality.js) and the crash guard.
export const FOREGROUND_GRACE_S = 3;
