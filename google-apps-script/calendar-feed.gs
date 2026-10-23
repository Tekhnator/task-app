/**
 * Отдаёт ваш Google Календарь в формате iCal (.ics) — с названиями, временем, местом и описанием.
 * Нужен, когда администратор Google Workspace запретил публичный/секретный адрес календаря.
 *
 * Установка: script.google.com → Новый проект → вставить этот код → Ctrl+S →
 * ⚙ Настройки проекта → часовой пояс (GMT+03:00) Москва →
 * «Начать развёртывание» → «Новое развёртывание» → тип «Веб-приложение»,
 * «Запуск от имени»: Я, «У кого есть доступ»: Все → скопировать URL …/exec.
 * В программе: ⚙ → «Календарь» → «Добавить календарь» → вставить URL.
 * После каждого изменения кода: «Управление развёртываниями» → ✏ → «Новая версия».
 */
const DAYS_BACK = 60, DAYS_AHEAD = 180;   // какой период отдавать в программу

function doGet() {
  const cal = CalendarApp.getDefaultCalendar();
  const now = Date.now();
  const evs = cal.getEvents(new Date(now - DAYS_BACK * 864e5), new Date(now + DAYS_AHEAD * 864e5));
  const pad = n => String(n).padStart(2, '0');
  const utc = d => d.getUTCFullYear() + pad(d.getUTCMonth() + 1) + pad(d.getUTCDate()) + 'T' + pad(d.getUTCHours()) + pad(d.getUTCMinutes()) + '00Z';
  const day = d => Utilities.formatDate(d, Session.getScriptTimeZone(), 'yyyyMMdd');
  const esc = s => String(s || '').replace(/\\/g, '\\\\').replace(/\r?\n/g, '\\n').replace(/[,;]/g, m => '\\' + m);
  const L = ['BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//Zadachi//RU', 'X-WR-CALNAME:' + esc(cal.getName())];
  evs.forEach(ev => {
    L.push('BEGIN:VEVENT', 'UID:' + ev.getId() + '-' + ev.getStartTime().getTime(), 'DTSTAMP:' + utc(new Date()));
    if (ev.isAllDayEvent()) L.push('DTSTART;VALUE=DATE:' + day(ev.getAllDayStartDate()), 'DTEND;VALUE=DATE:' + day(ev.getAllDayEndDate()));
    else L.push('DTSTART:' + utc(ev.getStartTime()), 'DTEND:' + utc(ev.getEndTime()));
    L.push('SUMMARY:' + esc(ev.getTitle() || '(без названия)'));
    if (ev.getLocation()) L.push('LOCATION:' + esc(ev.getLocation()));
    if (ev.getDescription()) L.push('DESCRIPTION:' + esc(ev.getDescription().replace(/<br\s*\/?>/gi, '\n').replace(/<[^>]+>/g, '')));
    L.push('END:VEVENT');
  });
  L.push('END:VCALENDAR');
  return ContentService.createTextOutput(L.join('\r\n')).setMimeType(ContentService.MimeType.ICAL);
}
