// Registration of the course screens, HUD and menu entry (SRT-004).
import { registerMenuEntry } from '../../menu.js';
import { registerRideHud } from '../ride-screen.js';
import { createCourseHud } from './course-hud.js';
import { createCourseSelectScreen, createPrestartScreen, createResultsScreen } from './screens.js';

export function registerCourses(app) {
  registerRideHud('course', createCourseHud);
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
