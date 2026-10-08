// Sleep session history kept in the phone's localStorage. The watch only holds
// ~2 hours, so every status update it sends is sampled here for the graphs and
// the Markdown export.
'use strict';

var SESSIONS_KEY = 'sleep_sessions';
var LAST_EXPORT_KEY = 'last_export';
var MAX_SESSIONS = 40;
var MAX_AGE_MS = 35 * 24 * 3600 * 1000; // covers the 30-day export range
var SAMPLE_GAP_MS = 45000;              // the watch sends several updates a minute
var BUCKET_MS = 5 * 60000;              // resolution used for graphs and export

function load() {
  try {
    return JSON.parse(localStorage.getItem(SESSIONS_KEY) || '[]');
  } catch (err) {
    return [];
  }
}

function save(sessions) {
  var cutoff = Date.now() - MAX_AGE_MS;
  sessions = sessions.filter(function (s) { return (s.end || s.start) >= cutoff; })
    .slice(-MAX_SESSIONS);
  // Out of storage: drop the oldest night and retry rather than lose the new one.
  while (sessions.length) {
    try {
      localStorage.setItem(SESSIONS_KEY, JSON.stringify(sessions));
      return;
    } catch (err) {
      sessions.shift();
    }
  }
}

// Drops finished sessions by start time (deleted in the Android app); the open one is never touched.
function removeSessions(starts) {
  var sessions = load();
  var kept = sessions.filter(function (s) { return !(s.end && starts.indexOf(s.start) !== -1); });
  if (kept.length !== sessions.length) save(kept);
}

// Called for every watch status message. Opens a session when tracking starts,
// appends at most one sample a minute, and closes the session when it stops.
function record(tracking, d, now) {
  var sessions = load();
  var current = sessions.length ? sessions[sessions.length - 1] : null;

  if (!tracking) {
    if (current && !current.end) {
      if (d.STATUS_ALARM_STATE !== undefined && d.STATUS_ALARM_STATE !== (current.alarm || 0)) {
        current.events = current.events || [];
        current.events.push({ t: now, a: d.STATUS_ALARM_STATE });
        current.alarm = d.STATUS_ALARM_STATE;
      }
      current.end = now;
      save(sessions);
    }
    return;
  }
  if (!current || current.end) {
    current = { start: now, end: 0, samples: [] };
    sessions.push(current);
  }
  // Cumulative for the session, so the latest message wins
  if (d.STATUS_SNOOZE_COUNT !== undefined) {
    current.snoozes = d.STATUS_SNOOZE_COUNT;
    current.snoozeSec = d.STATUS_SNOOZE_SEC || 0;
  }
  // Alarm timeline: 1 = rang, 2 = snoozed, 0 = stopped; only changes are logged
  if (d.STATUS_ALARM_STATE !== undefined && d.STATUS_ALARM_STATE !== (current.alarm || 0)) {
    current.events = current.events || [];
    current.events.push({ t: now, a: d.STATUS_ALARM_STATE });
    current.alarm = d.STATUS_ALARM_STATE;
  }
  var last = current.samples[current.samples.length - 1];
  if (d.STATUS_STATE !== undefined && (!last || now - last.t >= SAMPLE_GAP_MS)) {
    current.samples.push({
      t: now,
      s: d.STATUS_STATE,
      l: d.STATUS_LIGHT_LEVEL || 0,
      hr: d.STATUS_HEART_RATE || 0,
      m: d.STATUS_VMC, // movement; undefined (left out) for watch builds that do not send it
      n: d.STATUS_SOUND_LEVEL || 0
    });
  }
  save(sessions);
}

function mode(values) {
  var counts = {}, best = 0, bestCount = 0;
  values.forEach(function (v) {
    counts[v] = (counts[v] || 0) + 1;
    if (counts[v] > bestCount) { best = v; bestCount = counts[v]; }
  });
  return best;
}

// Average of the values that exist (zeros count: no movement is a reading), or undefined
function averageDefined(values) {
  var v = values.filter(function (x) { return x !== undefined; });
  if (!v.length) return undefined;
  return Math.round(v.reduce(function (a, b) { return a + b; }, 0) / v.length);
}

function averageNonZero(values) {
  var nz = values.filter(function (v) { return v > 0; });
  if (!nz.length) return 0;
  return Math.round(nz.reduce(function (a, b) { return a + b; }, 0) / nz.length);
}

// Collapse per-minute samples to 5-minute buckets so a month fits in the settings page.
function bucket(session) {
  var groups = [];
  session.samples.forEach(function (p) {
    var idx = Math.floor((p.t - session.start) / BUCKET_MS);
    var g = groups[groups.length - 1];
    if (!g || g.idx !== idx) {
      g = { idx: idx, t: p.t, items: [] };
      groups.push(g);
    }
    g.items.push(p);
  });
  var samples = groups.map(function (g) {
    var items = g.items;
    return {
      t: g.t,
      s: items[items.length - 1].s,
      l: mode(items.map(function (p) { return p.l; }).filter(function (v) { return v > 0; })),
      hr: averageNonZero(items.map(function (p) { return p.hr; })),
      n: averageNonZero(items.map(function (p) { return p.n; })),
      m: averageDefined(items.map(function (p) { return p.m; }))
    };
  });
  var lastT = session.samples.length ? session.samples[session.samples.length - 1].t : session.start;
  return {
    start: session.start,
    end: session.end || lastT,
    open: !session.end,
    snoozes: session.snoozes || 0,
    snoozeSec: session.snoozeSec || 0,
    events: session.events || [],
    samples: samples
  };
}

// Bucketed sessions that have at least two samples, oldest first.
function getBucketedSessions() {
  return load().filter(function (s) { return s.samples.length >= 2; }).map(bucket);
}

// Start time of the session being tracked right now, or 0
function openSessionStart() {
  var sessions = load();
  var current = sessions.length ? sessions[sessions.length - 1] : null;
  return current && !current.end ? current.start : 0;
}

function getLastExport() {
  return parseInt(localStorage.getItem(LAST_EXPORT_KEY) || '0', 10) || 0;
}

function setLastExport(ts) {
  localStorage.setItem(LAST_EXPORT_KEY, String(ts));
}

module.exports = {
  record: record,
  removeSessions: removeSessions,
  getBucketedSessions: getBucketedSessions,
  openSessionStart: openSessionStart,
  getLastExport: getLastExport,
  setLastExport: setLastExport
};
