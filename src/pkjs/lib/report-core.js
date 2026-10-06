// Graphs and Markdown export for sleep sessions.
//
// reportCore() is self-contained on purpose: the settings page is a data: URI
// with no access to this bundle, so config-page.js injects reportCore.toString()
// into the page and the same code builds the on-page graphs and the export.
// Sessions here are the bucketed form from history.js:
//   { start, end, open, samples: [{ t, s: stage, l: light, hr, n: noise }] }
'use strict';

function reportCore() {
  var STAGE_ROWS = [
    { name: 'Awake', value: 0, color: '#f5a623' },
    { name: 'REM',   value: 3, color: '#b57edc' },
    { name: 'Light', value: 1, color: '#4fd1c5' },
    { name: 'Deep',  value: 2, color: '#4c6ef5' }
  ];
  var LIGHT_ROWS = [
    { name: 'Bright', value: 4, color: '#ffe066' },
    { name: 'Light',  value: 3, color: '#f2c94c' },
    { name: 'Dark',   value: 2, color: '#8d7b3a' },
    { name: 'V.Dark', value: 1, color: '#4d4426' }
  ];
  var MIN = 60000;
  var ALARM_EVENTS = {
    1: { name: 'Alarm rang', word: 'rang', color: '#ff4d4f' },
    2: { name: 'Snoozed', word: 'snoozed', color: '#f5a623' },
    0: { name: 'Alarm stopped', word: 'stopped', color: '#34c759' }
  };

  function pad(n) { return ('0' + n).slice(-2); }
  function fmtDate(ms) {
    var d = new Date(ms);
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
  }
  function fmtTime(ms) {
    var d = new Date(ms);
    return pad(d.getHours()) + ':' + pad(d.getMinutes());
  }
  function fmtStamp(ms) { return fmtDate(ms) + ' ' + fmtTime(ms); }
  function fmtDur(ms) {
    var m = Math.round(ms / MIN);
    return Math.floor(m / 60) + 'h ' + pad(m % 60) + 'm';
  }

  // Samples with the time each one held until the next (or the session end)
  function spans(s) {
    return s.samples.map(function (p, i) {
      var to = i + 1 < s.samples.length ? s.samples[i + 1].t : s.end;
      return { from: p.t, to: Math.max(to, p.t), p: p };
    });
  }

  function summarize(s) {
    var mins = { 0: 0, 1: 0, 2: 0, 3: 0 };
    var hrSum = 0, hrN = 0, nSum = 0, nN = 0;
    spans(s).forEach(function (sp) {
      mins[sp.p.s] = (mins[sp.p.s] || 0) + (sp.to - sp.from) / MIN;
      if (sp.p.hr > 0) { hrSum += sp.p.hr; hrN++; }
      if (sp.p.n > 0) { nSum += sp.p.n; nN++; }
    });
    return {
      duration: s.end - s.start,
      awake: Math.round(mins[0]), light: Math.round(mins[1]),
      deep: Math.round(mins[2]), rem: Math.round(mins[3]),
      avgHr: hrN ? Math.round(hrSum / hrN) : 0,
      avgNoise: nN ? Math.round(nSum / nN) : 0,
      snoozes: s.snoozes || 0,
      snoozeMin: Math.round((s.snoozeSec || 0) / 60)
    };
  }

  function snoozeText(m) {
    return m.snoozes ? m.snoozes + (m.snoozes === 1 ? ' snooze' : ' snoozes') + ' (' + m.snoozeMin + ' min)' : 'none';
  }

  function alarmEvents(s) {
    return (s.events || []).filter(function (e) { return e.t >= s.start && e.t <= s.end && ALARM_EVENTS[e.a]; });
  }

  function alarmText(s) {
    var ev = alarmEvents(s);
    return ev.length ? ev.map(function (e) { return ALARM_EVENTS[e.a].word + ' ' + fmtTime(e.t); }).join(' \u00b7 ') : 'none';
  }

  // Vertical lines for alarm events over a plot area
  function eventMarks(s, left, width, top, height) {
    var span = Math.max(s.end - s.start, 1);
    return alarmEvents(s).map(function (e) {
      var x = (left + (e.t - s.start) / span * width).toFixed(1);
      return '<line x1="' + x + '" x2="' + x + '" y1="' + top + '" y2="' + (top + height) +
        '" stroke="' + ALARM_EVENTS[e.a].color + '" stroke-width="2"/>';
    }).join('');
  }

  function alarmLegend(s) {
    if (!alarmEvents(s).length) return '';
    return '<p class="hint">Alarm: ' + [1, 2, 0].map(function (k) {
      return '<span style="color:' + ALARM_EVENTS[k].color + '">&#9679;</span> ' + ALARM_EVENTS[k].word;
    }).join(' &nbsp; ') + '</p>';
  }

  // Settings-page numbers for one session
  function sessionStats(s) {
    var m = summarize(s);
    var rows = [
      ['Time in bed', fmtDur(m.duration)],
      ['Awake / Light / Deep / REM', m.awake + ' / ' + m.light + ' / ' + m.deep + ' / ' + m.rem + ' min'],
      ['Average heart rate', m.avgHr ? m.avgHr + ' bpm' : '-'],
      ['Average room noise', m.avgNoise ? m.avgNoise + ' dB' : '-'],
      ['Snoozed', snoozeText(m)],
      ['Alarm', alarmText(s)]
    ];
    return '<table class="stats">' + rows.map(function (r) {
      return '<tr><td>' + r[0] + '</td><td>' + r[1] + '</td></tr>';
    }).join('') + '</table>';
  }

  // ---- On-page SVG graphs (colors come from the page's CSS variables) ----

  function stepSvg(s, field, rows) {
    var W = 320, ROW = 24, LEFT = 44, plotW = W - LEFT - 4;
    var span = Math.max(s.end - s.start, 1);
    var svg = '<svg viewBox="0 0 ' + W + ' ' + (ROW * rows.length + 18) + '" width="100%">';
    rows.forEach(function (r, i) {
      svg += '<text class="ax" x="0" y="' + (i * ROW + 16) + '">' + r.name + '</text>' +
        '<rect class="plot" x="' + LEFT + '" y="' + (i * ROW + 2) + '" width="' + plotW + '" height="' + (ROW - 4) + '"/>';
    });
    spans(s).forEach(function (sp) {
      var row = -1;
      rows.forEach(function (r, i) { if (r.value === sp.p[field]) row = i; });
      if (row < 0) return; // no reading (sensor off)
      var x = LEFT + (sp.from - s.start) / span * plotW;
      var w = Math.max((sp.to - sp.from) / span * plotW, 1);
      svg += '<rect x="' + x.toFixed(1) + '" y="' + (row * ROW + 2) + '" width="' + w.toFixed(1) +
        '" height="' + (ROW - 4) + '" fill="' + rows[row].color + '"/>';
    });
    svg += eventMarks(s, LEFT, plotW, 2, ROW * rows.length - 4);
    var by = ROW * rows.length + 14;
    return svg + '<text class="ax" x="' + LEFT + '" y="' + by + '">' + fmtTime(s.start) + '</text>' +
      '<text class="ax" x="' + (W - 4) + '" y="' + by + '" text-anchor="end">' + fmtTime(s.end) + '</text></svg>';
  }

  function lineSvg(s, field, color, unit) {
    var pts = s.samples.filter(function (p) { return p[field] > 0; });
    if (pts.length < 2) return '<p class="hint">No ' + unit + ' data for this session.</p>';
    var W = 320, H = 110, LEFT = 30, span = Math.max(s.end - s.start, 1);
    var min = Infinity, max = -Infinity, sum = 0;
    pts.forEach(function (p) { min = Math.min(min, p[field]); max = Math.max(max, p[field]); sum += p[field]; });
    var lo = Math.floor(min / 5) * 5, hi = Math.max(Math.ceil(max / 5) * 5, lo + 5);
    var line = pts.map(function (p) {
      var x = LEFT + (p.t - s.start) / span * (W - LEFT - 4);
      var y = 6 + (1 - (p[field] - lo) / (hi - lo)) * (H - 24);
      return x.toFixed(1) + ',' + y.toFixed(1);
    }).join(' ');
    return '<svg viewBox="0 0 ' + W + ' ' + H + '" width="100%">' +
      '<text class="ax" x="0" y="12">' + hi + '</text>' +
      '<text class="ax" x="0" y="' + (H - 18) + '">' + lo + '</text>' +
      '<rect class="plot" x="' + LEFT + '" y="6" width="' + (W - LEFT - 4) + '" height="' + (H - 24) + '"/>' +
      '<polyline points="' + line + '" fill="none" stroke="' + color + '" stroke-width="1.5"/>' +
      eventMarks(s, LEFT, W - LEFT - 4, 6, H - 24) +
      '<text class="ax" x="' + LEFT + '" y="' + (H - 4) + '">' + fmtTime(s.start) + '</text>' +
      '<text class="ax" x="' + (W - 4) + '" y="' + (H - 4) + '" text-anchor="end">' + fmtTime(s.end) + '</text></svg>' +
      '<p class="hint">min ' + min + ' &middot; avg ' + Math.round(sum / pts.length) + ' &middot; max ' + max + ' ' + unit + '</p>';
  }

  // Settings-page graphs for one session
  function sessionGraphs(s) {
    var hasLight = s.samples.some(function (p) { return p.l > 0; });
    return '<h3>Sleep stages</h3>' + stepSvg(s, 's', STAGE_ROWS) + alarmLegend(s) +
      '<h3>Heart rate</h3>' + lineSvg(s, 'hr', '#ff6b6b', 'bpm') +
      '<h3>Ambient light</h3>' + (hasLight ? stepSvg(s, 'l', LIGHT_ROWS) : '<p class="hint">No light data for this session.</p>') +
      '<h3>Room noise</h3>' + lineSvg(s, 'n', '#e0a800', 'dB');
  }

  function sessionLabel(s) {
    var d = new Date(s.start);
    var day = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][d.getDay()];
    return day + ' ' + fmtDate(s.start) + ' · ' + fmtTime(s.start) + '–' + fmtTime(s.end) +
      ' (' + fmtDur(s.end - s.start) + ')' + (s.open ? ' · tracking' : '');
  }

  // ---- Markdown + mermaid export ----

  function ganttBlock(s) {
    var merged = [];
    spans(s).forEach(function (sp) {
      var last = merged[merged.length - 1];
      if (last && last.stage === sp.p.s && last.to === sp.from) {
        last.to = sp.to;
      } else {
        merged.push({ stage: sp.p.s, from: sp.from, to: sp.to });
      }
    });
    var out = ['```mermaid', 'gantt', '    title Sleep stages', '    dateFormat YYYY-MM-DD HH:mm',
      '    axisFormat %H:%M'];
    STAGE_ROWS.forEach(function (r) {
      var segs = merged.filter(function (m) { return m.stage === r.value && m.to > m.from; });
      if (!segs.length) return;
      out.push('    section ' + r.name);
      segs.forEach(function (m) {
        out.push('    ' + r.name + ' :' + fmtStamp(m.from) + ', ' + fmtStamp(m.to));
      });
    });
    var ev = alarmEvents(s);
    if (ev.length) {
      out.push('    section Alarm');
      ev.forEach(function (e) {
        out.push('    ' + ALARM_EVENTS[e.a].name + ' :milestone, ' + fmtStamp(e.t) + ', 0m');
      });
    }
    out.push('```');
    return out.join('\n');
  }

  // Resample a field to <= ~24 evenly spaced points (15-minute multiples), filling gaps
  function series(s, field) {
    var span = s.end - s.start;
    var step = Math.max(15 * MIN, Math.ceil(span / 24 / (15 * MIN)) * 15 * MIN);
    var n = Math.max(Math.ceil(span / step), 1);
    var sums = [], counts = [];
    for (var i = 0; i < n; i++) { sums.push(0); counts.push(0); }
    s.samples.forEach(function (p) {
      if (p[field] > 0) {
        var i2 = Math.min(Math.floor((p.t - s.start) / step), n - 1);
        sums[i2] += p[field]; counts[i2]++;
      }
    });
    var values = [], labels = [], prev = 0;
    for (var j = 0; j < n; j++) {
      var v = counts[j] ? Math.round(sums[j] / counts[j]) : prev;
      if (v) prev = v;
      values.push(v);
      labels.push('"' + fmtTime(s.start + j * step) + '"');
    }
    // Leading gap: back-fill from the first real reading
    var first = values.filter(function (v) { return v > 0; })[0];
    if (!first) return null;
    values = values.map(function (v) { return v || first; });
    return { values: values, labels: labels };
  }

  function chartBlock(s, field, title, axis, lo, hi) {
    var d = series(s, field);
    if (!d) return '';
    var min = Math.min.apply(null, d.values), max = Math.max.apply(null, d.values);
    // Callers pass fixed bounds (light: 0-4) or null to fit the data to multiples of 5
    if (lo === null) lo = Math.floor(min / 5) * 5;
    if (hi === null) hi = Math.max(Math.ceil(max / 5) * 5, lo + 5);
    return ['```mermaid', 'xychart-beta', '    title "' + title + '"',
      '    x-axis [' + d.labels.join(', ') + ']',
      '    y-axis "' + axis + '" ' + lo + ' --> ' + hi,
      '    line [' + d.values.join(', ') + ']', '```'].join('\n');
  }

  function exportMarkdown(sessions, rangeLabel, now) {
    var out = ['# SleepSense export', '',
      '**Range:** ' + rangeLabel + ' \u00b7 **Sessions:** ' + sessions.length +
      ' \u00b7 **Generated:** ' + fmtStamp(now), ''];
    if (!sessions.length) {
      out.push('No sleep sessions in this range.');
      return out.join('\n') + '\n';
    }
    out.push('## Summary', '',
      '| Date | Time | Duration | Awake | Light | Deep | REM | Avg HR | Avg noise | Snoozes |',
      '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |');
    sessions.forEach(function (s) {
      var m = summarize(s);
      out.push('| ' + fmtDate(s.start) + ' | ' + fmtTime(s.start) + '–' + fmtTime(s.end) +
        ' | ' + fmtDur(m.duration) + ' | ' + m.awake + 'm | ' + m.light + 'm | ' + m.deep + 'm | ' +
        m.rem + 'm | ' + (m.avgHr ? m.avgHr + ' bpm' : '-') + ' | ' + (m.avgNoise ? m.avgNoise + ' dB' : '-') + ' | ' +
        (m.snoozes ? m.snoozes + ' (' + m.snoozeMin + ' min)' : '-') + ' |');
    });
    out.push('');
    sessions.slice().reverse().forEach(function (s) {
      out.push('## ' + sessionLabel(s), '');
      if (alarmEvents(s).length) out.push('**Alarm:** ' + alarmText(s), '');
      out.push(ganttBlock(s), '');
      [chartBlock(s, 'hr', 'Heart rate', 'bpm', null, null),
        chartBlock(s, 'l', 'Ambient light level (1 dark to 4 bright)', 'level', 0, 4),
        chartBlock(s, 'n', 'Room noise', 'dB', null, null)].forEach(function (block) {
        if (block) out.push(block, '');
      });
    });
    return out.join('\n');
  }

  return {
    fmtDate: fmtDate,
    fmtStamp: fmtStamp,
    sessionLabel: sessionLabel,
    sessionStats: sessionStats,
    sessionGraphs: sessionGraphs,
    exportMarkdown: exportMarkdown
  };
}

module.exports = reportCore;
