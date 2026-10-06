// Builds the settings page opened via Pebble.openURL() from showConfiguration.
// Inlined as a data: URI (the .pbw can't bundle a hosted page); layout and
// styling follow the other Pebble projects' settings pages. Graphs and the
// Markdown export are rendered inside the page from the embedded session data
// by reportCore (see report-core.js).
'use strict';

var reportCore = require('./report-core');

function escapeHtmlAttr(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

// JSON embedded in a <script>: keep "</script>" and friends from ending the block early.
function jsonForScript(value) {
  return JSON.stringify(value).replace(/</g, '\\u003c').replace(/[\u2028\u2029]/g, '');
}

// Material icons (Apache License 2.0), drawn in the button's text colour
function icon(path) {
  return '<svg class="ic" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="' + path + '"/></svg>';
}
var ICON_COPY = icon('M16,1L4,1c-1.1,0 -2,0.9 -2,2v14h2L4,3h12L16,1zM19,5L8,5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2L21,7c0,-1.1 -0.9,-2 -2,-2zM19,21L8,21L8,7h11v14z');
var ICON_SAVE = icon('M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z');

function pad(n) { return ('0' + n).slice(-2); }

function options(list, selected) {
  return list.map(function (opt) {
    return '<option value="' + opt[0] + '"' + (opt[0] === selected ? ' selected' : '') + '>' + opt[1] + '</option>';
  }).join('\n');
}

function checkbox(id, label, hint, checked) {
  return '  <div class="checkbox-row">\n' +
    '    <input id="' + id + '" type="checkbox"' + (checked ? ' checked' : '') + '>\n' +
    '    <label for="' + id + '">' + label + '</label>\n' +
    '  </div>\n' +
    '  <p class="hint">' + hint + '</p>\n';
}

function buildConfigPageUrl(opts) {
  var alarm = opts.alarm;
  var sensors = opts.sensors;
  var data = {
    sessions: opts.sessions,
    lastExport: opts.lastExport,
    now: opts.now
  };

  var html = '<!doctype html>\n' +
'<html lang="en">\n' +
'<head>\n' +
'<meta charset="utf-8">\n' +
'<meta name="viewport" content="width=device-width, initial-scale=1">\n' +
'<meta name="color-scheme" content="light dark">\n' +
'<title>SleepSense settings</title>\n' +
'<style>\n' +
'  :root { --bg: #fff; --fg: #111; --hint: #666; --field-bg: #fff; --field-border: #ccc;\n' +
'          --btn-2-bg: #eee; --btn-2-fg: #111; --danger: #c00; --ok: #0a0; }\n' +
'  @media (prefers-color-scheme: dark) {\n' +
'    :root { --bg: #1c1c1e; --fg: #e6e6e9; --hint: #9a9aa0; --field-bg: #2c2c2e; --field-border: #48484a;\n' +
'            --btn-2-bg: #2c2c2e; --btn-2-fg: #e6e6e9; --danger: #ff6b6b; --ok: #4ade80; }\n' +
'  }\n' +
'  body { font-family: -apple-system, Roboto, sans-serif; margin: 0; padding: 16px; background: var(--bg); color: var(--fg); }\n' +
'  h1 { font-size: 18px; }\n' +
'  h2 { font-size: 15px; margin-top: 24px; }\n' +
'  h3 { font-size: 13px; margin: 14px 0 4px; color: var(--hint); }\n' +
'  label { display: block; margin-top: 14px; font-size: 13px; font-weight: 600; }\n' +
'  input, select { width: 100%; box-sizing: border-box; padding: 10px; font-size: 15px; margin-top: 4px; border: 1px solid var(--field-border); border-radius: 6px; background: var(--field-bg); color: var(--fg); }\n' +
'  .checkbox-row { display: flex; align-items: center; gap: 8px; margin-top: 14px; }\n' +
'  .checkbox-row input { width: auto; margin: 0; }\n' +
'  .checkbox-row label { display: inline; margin: 0; font-weight: normal; }\n' +
'  button { width: 100%; padding: 12px; font-size: 15px; margin-top: 16px; border: none; border-radius: 6px; background: #1a73e8; color: #fff; }\n' +
'  .ic { width: 18px; height: 18px; vertical-align: -4px; margin-right: 8px; }\n' +
'  button.secondary { background: var(--btn-2-bg); color: var(--btn-2-fg); margin-top: 8px; }\n' +
'  p.hint { font-size: 12px; color: var(--hint); }\n' +
'  p.error { font-size: 13px; color: var(--danger); }\n' +
'  p.success { font-size: 13px; color: var(--ok); }\n' +
'  table.stats { width: 100%; border-collapse: collapse; margin-top: 10px; font-size: 13px; }\n' +
'  table.stats td { padding: 6px 0; border-bottom: 1px solid var(--field-border); }\n' +
'  table.stats td:last-child { text-align: right; font-weight: 600; }\n' +
'  svg .ax { fill: var(--hint); font-size: 11px; }\n' +
'  svg .plot { fill: var(--field-bg); stroke: var(--field-border); stroke-width: 0.5; }\n' +
'  textarea { width: 100%; box-sizing: border-box; height: 160px; margin-top: 4px; padding: 10px;\n' +
'    font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12px; white-space: pre;\n' +
'    border: 1px solid var(--field-border); border-radius: 6px; background: var(--field-bg); color: var(--fg); }\n' +
'</style>\n' +
'</head>\n' +
'<body>\n' +
'  <h1>SleepSense settings</h1>\n' +
'  <p class="hint">Sleep tracking and the smart wake alarm run on the watch; settings and history live here on the phone.</p>\n' +
'\n' +
'  <h2>Smart wake alarm</h2>\n' +
checkbox('alarmEn', 'Smart alarm',
  'Vibrates gently when you are in light sleep inside the wake window, or at the exact time if you are not.', alarm.enabled) +
'  <label for="alarmTime">Wake time</label>\n' +
'  <input id="alarmTime" type="time" value="' + pad(alarm.hour) + ':' + pad(alarm.min) + '">\n' +
'\n' +
'  <label for="smartWin">Smart wake window</label>\n' +
'  <select id="smartWin">\n' +
options([[15, '15 minutes before'], [30, '30 minutes before'], [45, '45 minutes before']], alarm.window) + '\n' +
'  </select>\n' +
'  <p class="hint">How early the alarm may go off if you are in light sleep.</p>\n' +
'\n' +
'  <label for="snoozeMin">Snooze</label>\n' +
'  <select id="snoozeMin">\n' +
options([[0, 'Off'], [5, '5 minutes'], [9, '9 minutes'], [10, '10 minutes'], [15, '15 minutes']], alarm.snooze) + '\n' +
'  </select>\n' +
'  <p class="hint">While the alarm rings, Select stops it and Down snoozes it for this long. Off makes every button stop it.</p>\n' +
'\n' +
'  <h2>Sensors</h2>\n' +
'  <p class="hint">Turn off anything you do not want used for sleep staging or shown on the watch.</p>\n' +
checkbox('lightEn', 'Ambient light', 'Room light from the watch. Bright light caps sleep at Light.', sensors.light) +
checkbox('micEn', 'Room noise', 'Noise level sampled by the phone. Loud noise with movement counts as waking.', sensors.mic) +
checkbox('hrEn', 'Heart rate', 'Samples every minute while tracking. Needs a watch with a heart rate sensor; uses more battery.', sensors.hr) +
'\n' +
'  <h2>Sleep history</h2>\n' +
'  <p class="hint">Graphs for a recorded session. The phone keeps your last 35 days.</p>\n' +
'  <div id="historyEmpty" class="hint" style="display:none">No sessions yet. Start tracking on the watch (Select) and they appear here.</div>\n' +
'  <div id="historyBox">\n' +
'    <label for="sessionPick">Session</label>\n' +
'    <select id="sessionPick"></select>\n' +
'    <div id="graphs"></div>\n' +
'  </div>\n' +
'\n' +
'  <h2>Export</h2>\n' +
'  <p class="hint">Your sleep data as Markdown with mermaid charts (stage timeline, heart rate, light, noise). Copy it anywhere that renders mermaid. Sessions still being tracked are left out.</p>\n' +
'  <label for="range">Date range</label>\n' +
'  <select id="range">\n' +
'    <option value="7">Last 7 days</option>\n' +
'    <option value="14">Last 14 days</option>\n' +
'    <option value="30">Last 30 days</option>\n' +
'    <option value="since" id="sinceOpt">Since last export</option>\n' +
'  </select>\n' +
'  <p class="hint" id="exportInfo"></p>\n' +
'  <textarea id="exportText" readonly></textarea>\n' +
'  <button id="copyBtn" class="secondary">'+ICON_COPY+'Copy to clipboard</button>\n' +
'  <button id="downloadBtn" class="secondary">'+ICON_SAVE+'Download .md file</button>\n' +
'  <p id="status"></p>\n' +
'\n' +
'  <button id="saveBtn">Save &amp; sync</button>\n' +
'  <button id="cancelBtn" class="secondary">Cancel</button>\n' +
'\n' +
'<script>\n' +
'(function () {\n' +
'  var report = (' + reportCore.toString() + ')();\n' +
'  var DATA = ' + jsonForScript(data) + ';\n' +
'  var exportedAt = 0; // set when the user copies/downloads; saved by the watch app\n' +
'\n' +
'  function $(id) { return document.getElementById(id); }\n' +
'  function setStatus(msg, isError) {\n' +
'    $(\'status\').textContent = msg;\n' +
'    $(\'status\').className = isError ? \'error\' : \'success\';\n' +
'  }\n' +
'  function returnToWatchApp(result) {\n' +
'    if (exportedAt) { result.exportedAt = exportedAt; }\n' +
'    location.href = \'pebblejs://close#\' + encodeURIComponent(JSON.stringify(result));\n' +
'  }\n' +
'\n' +
'  // ---- Session picker ----\n' +
'  var sessions = DATA.sessions; // oldest first\n' +
'  var pick = $(\'sessionPick\');\n' +
'  if (!sessions.length) {\n' +
'    $(\'historyBox\').style.display = \'none\';\n' +
'    $(\'historyEmpty\').style.display = \'block\';\n' +
'  }\n' +
'  sessions.slice().reverse().forEach(function (s) {\n' +
'    var opt = document.createElement(\'option\');\n' +
'    opt.value = String(sessions.indexOf(s));\n' +
'    opt.textContent = report.sessionLabel(s);\n' +
'    pick.appendChild(opt);\n' +
'  });\n' +
'  function showSession() {\n' +
'    if (!sessions.length) { return; }\n' +
'    var s = sessions[parseInt(pick.value, 10)];\n' +
'    $(\'graphs\').innerHTML = report.sessionStats(s) + report.sessionGraphs(s);\n' +
'  }\n' +
'  pick.addEventListener(\'change\', showSession);\n' +
'  showSession();\n' +
'\n' +
'  // ---- Export ----\n' +
'  var DAY = 86400000;\n' +
'  var sinceOpt = $(\'sinceOpt\');\n' +
'  sinceOpt.textContent = DATA.lastExport\n' +
'    ? \'Since last export (\' + report.fmtStamp(DATA.lastExport) + \')\'\n' +
'    : \'Since last export (never exported - everything)\';\n' +
'  var RANGE_LABELS = { 7: \'Last 7 days\', 14: \'Last 14 days\', 30: \'Last 30 days\' };\n' +
'\n' +
'  function currentExport() {\n' +
'    var range = $(\'range\').value;\n' +
'    var finished = sessions.filter(function (s) { return !s.open; });\n' +
'    var picked, label;\n' +
'    if (range === \'since\') {\n' +
'      picked = finished.filter(function (s) { return s.end > DATA.lastExport; });\n' +
'      label = DATA.lastExport ? \'Since last export (\' + report.fmtStamp(DATA.lastExport) + \')\' : \'Everything (never exported)\';\n' +
'    } else {\n' +
'      var cutoff = DATA.now - parseInt(range, 10) * DAY;\n' +
'      picked = finished.filter(function (s) { return s.end >= cutoff; });\n' +
'      label = RANGE_LABELS[range];\n' +
'    }\n' +
'    return { sessions: picked, text: report.exportMarkdown(picked, label, DATA.now) };\n' +
'  }\n' +
'  function refreshExport() {\n' +
'    var e = currentExport();\n' +
'    $(\'exportText\').value = e.text;\n' +
'    $(\'exportInfo\').textContent = e.sessions.length + (e.sessions.length === 1 ? \' session\' : \' sessions\') + \' in this range.\';\n' +
'  }\n' +
'  $(\'range\').addEventListener(\'change\', refreshExport);\n' +
'  refreshExport();\n' +
'\n' +
'  function markExported(msg) {\n' +
'    exportedAt = Date.now();\n' +
'    setStatus(msg + \' Tap Save & sync to remember this export for "Since last export".\', false);\n' +
'  }\n' +
'  $(\'copyBtn\').addEventListener(\'click\', function () {\n' +
'    var ta = $(\'exportText\');\n' +
'    var done = function () { markExported(\'Copied.\'); };\n' +
'    var fail = function () {\n' +
'      ta.focus(); ta.select();\n' +
'      setStatus(\'Press and hold the box, then Copy.\', true);\n' +
'    };\n' +
'    try {\n' +
'      if (navigator.clipboard && navigator.clipboard.writeText) {\n' +
'        navigator.clipboard.writeText(ta.value).then(done, function () {\n' +
'          try { ta.focus(); ta.select(); document.execCommand(\'copy\') ? done() : fail(); }\n' +
'          catch (e2) { fail(); }\n' +
'        });\n' +
'      } else {\n' +
'        ta.focus(); ta.select();\n' +
'        document.execCommand(\'copy\') ? done() : fail();\n' +
'      }\n' +
'    } catch (e) { fail(); }\n' +
'  });\n' +
'  $(\'downloadBtn\').addEventListener(\'click\', function () {\n' +
'    var a = document.createElement(\'a\');\n' +
'    a.href = \'data:text/markdown;charset=utf-8,\' + encodeURIComponent($(\'exportText\').value);\n' +
'    a.download = \'sleepsense-\' + report.fmtDate(DATA.now) + \'.md\';\n' +
'    document.body.appendChild(a);\n' +
'    a.click();\n' +
'    document.body.removeChild(a);\n' +
'    markExported(\'Download started (if nothing saved, use Copy instead).\');\n' +
'  });\n' +
'\n' +
'  // ---- Save / cancel ----\n' +
'  $(\'saveBtn\').addEventListener(\'click\', function () {\n' +
'    var t = ($(\'alarmTime\').value || \'07:00\').split(\':\');\n' +
'    returnToWatchApp({\n' +
'      alarm_en: $(\'alarmEn\').checked,\n' +
'      alarm_hour: parseInt(t[0], 10),\n' +
'      alarm_min: parseInt(t[1], 10),\n' +
'      smart_win: parseInt($(\'smartWin\').value, 10),\n' +
'      snooze_min: parseInt($(\'snoozeMin\').value, 10),\n' +
'      light_en: $(\'lightEn\').checked,\n' +
'      mic_en: $(\'micEn\').checked,\n' +
'      hr_en: $(\'hrEn\').checked\n' +
'    });\n' +
'  });\n' +
'  $(\'cancelBtn\').addEventListener(\'click\', function () {\n' +
'    returnToWatchApp({ cancelled: true });\n' +
'  });\n' +
'})();\n' +
'</script>\n' +
'  <p class="hint" style="text-align:center;margin-top:24px">SleepSense for Pebble' +
  (opts.appVersion ? ' &middot; v' + escapeHtmlAttr(opts.appVersion) : '') + '</p>\n' +
'</body>\n' +
'</html>\n';

  return 'data:text/html;charset=utf-8,' + encodeURIComponent(html);
}

module.exports = {
  buildConfigPageUrl: buildConfigPageUrl
};
