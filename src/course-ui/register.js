// Anmeldung des Parcours-Teils (SRT-004).
import { registerMenuEntry } from '../app/menu.js';
import { registerRideMode } from '../game/ride-screen.js';
import { createCourseMode } from './course-mode.js';
import { createCourseSelectScreen, createPrestartScreen, createResultsScreen } from './screens.js';

export function registerCourses(app) {
  registerRideMode('course', createCourseMode);
  app.register('courseSelect', createCourseSelectScreen);
  app.register('prestart', createPrestartScreen);
  app.register('results', createResultsScreen);
  registerMenuEntry({
    id: 'courses',
    order: 10,
    labelKey: 'menu.courses',
    onSelect: ({ app }) => app.go('courseSelect'),
  });
}
