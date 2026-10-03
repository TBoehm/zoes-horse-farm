// Horse appearance values the player can choose from (concept: "My horse").
export const COATS = ['chestnut', 'bay', 'black', 'grey', 'pinto'];
export const MARKINGS = ['none', 'star', 'blaze', 'snip'];

/** Appearance of a new horse; single source for the save schema and the 3D view. */
export const DEFAULT_APPEARANCE = Object.freeze({ coat: 'bay', marking: 'star' });
