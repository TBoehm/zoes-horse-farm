// Display of ride times in hundredths of a second as mm:ss,hh (glossary "ride time").
export function formatCs(cs, lang = 'de') {
  const total = Math.max(0, Math.round(cs));
  const minutes = Math.floor(total / 6000);
  const seconds = Math.floor((total % 6000) / 100);
  const hundredths = total % 100;
  const sep = lang === 'de' ? ',' : '.';
  const two = (n) => String(n).padStart(2, '0');
  return `${two(minutes)}:${two(seconds)}${sep}${two(hundredths)}`;
}
