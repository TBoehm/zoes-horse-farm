// Anmeldung des Parcours-Teils (SRT-004).
import { registerMenuEntry } from '../../menu.js';
import { registerRideMode } from '../ride-screen.js';
import { createCourseMode } from '../../../../application/modes/course-mode.js';
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
