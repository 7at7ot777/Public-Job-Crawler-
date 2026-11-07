/**
 * Sends one alert to Telegram and exits: 0 when it went through, 1 when it did
 * not. For docker-crawl-loop.sh, which has no Telegram client of its own and
 * needs to know whether to try again — the outage it is reporting may well
 * still be cutting this message off too.
 *
 *   node src/notify.js "Recruitera failed 3 times in a row"
 *
 * The text is plain and is escaped here. It goes to TELEGRAM_ALERT_CHAT_ID,
 * or the main chat when that is unset, like the pipeline's own alerts.
 */
import config from './config.js';
import TelegramClient from './telegram.js';

const text = process.argv.slice(2).join(' ').trim();

const escapeHtml = (value) =>
  value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

if (text === '') {
  console.error('Usage: node src/notify.js "<message>"');
  process.exit(2);
}

const telegram = new TelegramClient();

if (!config.alerts.enabled || !telegram.isEnabled()) {
  // Nothing to send to, which retrying will not change: report it as done.
  console.log('Alerts are off or Telegram is not configured; not sent.');
  process.exit(0);
}

try {
  await telegram.sendMessage(`⚠️ <b>Crawler alert</b>\n${escapeHtml(text)}`, config.alerts.chatId);
  process.exit(0);
} catch (error) {
  console.error(`Alert not sent: ${error.message}`);
  process.exit(1);
}
