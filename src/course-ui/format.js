// Anzeige von Ritt-Zeiten in Hundertstel (Begriffe „Ritt-Zeit").
export function formatCs(cs, lang = 'de') {
  const total = Math.max(0, Math.round(cs));
  const minutes = Math.floor(total / 6000);
  const seconds = Math.floor((total % 6000) / 100);
  const hundredths = total % 100;
  const sep = lang === 'de' ? ',' : '.';
  return `${minutes}:${String(seconds).padStart(2, '0')}${sep}${String(hundredths).padStart(2, '0')}`;
}

export function formatSeconds(cs, lang = 'de') {
  const total = Math.max(0, Math.floor(cs));
  const sep = lang === 'de' ? ',' : '.';
  return `${Math.floor(total / 100)}${sep}${String(total % 100).padStart(2, '0')}`;
}
