// PebbleKit JS Companion for SleepSense
var sleepHistory = require("./lib/history");
var configPage = require("./lib/config-page");
var phoneAlarm = require("./lib/phone-alarm");
var sessionPush = require("./lib/session-push");
var noiseBridge = require("./lib/noise-bridge");

var APP_VERSION = "1.0.0"; // keep in step with package.json

// Messages to the watch go through one queue: the watch takes one at a time, so two sent together
// (e.g. the startup settings and a "start tracking" command right after the app launches) would
// collide and one would be lost. Each is retried a few times if the watch is busy.
var outbox = [];
var outboxBusy = false;

function sendToWatch(dict, onOk, onFail) {
  outbox.push({ dict: dict, onOk: onOk, onFail: onFail, tries: 0 });
  pumpOutbox();
}

function pumpOutbox() {
  if (outboxBusy || !outbox.length) return;
  outboxBusy = true;
  var item = outbox[0];
  Pebble.sendAppMessage(item.dict, function() {
    outbox.shift();
    outboxBusy = false;
    if (item.onOk) item.onOk();
    pumpOutbox();
  }, function(err) {
    outboxBusy = false;
    item.tries++;
    if (item.tries >= 5) {
      outbox.shift();
      if (item.onFail) item.onFail(err);
    }
    setTimeout(pumpOutbox, 1500);
  });
}

var soundInterval = null;
var isTracking = false;

// Sensor toggles (default on); stored as "true"/"false" strings
function sensorEnabled(name) {
  return localStorage.getItem(name) !== "false";
}

function snoozeMinutes() {
  var saved = localStorage.getItem("snooze_min");
  return saved === null ? 9 : parseInt(saved, 10);
}

function sensorDict() {
  return {
    SENSOR_LIGHT_ENABLED: sensorEnabled("light_en") ? 1 : 0,
    SENSOR_MIC_ENABLED: sensorEnabled("mic_en") ? 1 : 0,
    SENSOR_HR_ENABLED: sensorEnabled("hr_en") ? 1 : 0
  };
}

var stageNames = ["AWAKE", "LIGHT", "DEEP", "REM"];
var lightNames = ["Unknown", "Very Dark", "Dark", "Light", "Very Light"];

// While tracking, the Android app listens through the phone microphone (and saves a clip on
// a loud spike). Once a minute its average level goes to the watch as the room noise.
// Without the app, or with noise monitoring switched off there, no level is sent.
function startSoundMonitoring() {
  if (soundInterval) return;
  console.log("SleepSense PKJS: Starting phone noise monitoring");
  noiseBridge.start(function(r) {
    console.log("SleepSense PKJS: Noise monitor: " + JSON.stringify(r));
  });

  soundInterval = setInterval(function() {
    if (!isTracking || !sensorEnabled("mic_en")) {
      stopSoundMonitoring();
      return;
    }
    noiseBridge.status(function(n) {
      if (!n || !n.listening || !n.avg) return;
      sendToWatch({ PHONE_SOUND_SAMPLE: n.avg }, function() {
        console.log("SleepSense PKJS: Room noise " + n.avg + " dB sent");
      }, function(e) {
        console.log("SleepSense PKJS: Error sending noise level: " + JSON.stringify(e));
      });
    });
  }, 60000);
}

function stopSoundMonitoring() {
  if (soundInterval) {
    clearInterval(soundInterval);
    soundInterval = null;
    noiseBridge.stop();
    console.log("SleepSense PKJS: Stopped phone sound monitoring");
  }
}

// ---- Sleep history for the Android app ----
var lastPush = 0;
var PUSH_EVERY_MS = 10 * 60000; // while tracking, so tonight's graph stays fresh
var PUSH_MIN_GAP_MS = 20000;

// Sends the whole history; `force` for a session that just ended, else at most every 10 minutes
function pushHistory(force) {
  var now = Date.now();
  if (now - lastPush < (force ? PUSH_MIN_GAP_MS : PUSH_EVERY_MS)) return;
  lastPush = now;
  sessionPush.pushSessions(sleepHistory.getBucketedSessions(), function(ok) {
    if (!ok) lastPush = 0; // no app there (or it failed): try again at the next chance
  });
}

// ---- Phone widget: status out, "start tracking" command in ----

function trackingStatus() {
  return { tracking: isTracking, since: sleepHistory.openSessionStart() };
}

// Tell the Android app right away when tracking starts or stops (the minute poll also carries it)
function reportStatus() {
  phoneAlarm.fetchPhoneAlarm(function() {}, trackingStatus());
}

// Sends a start/stop command and repeats it until the watch reflects it: it can be lost when the
// watch app is being restarted at that moment (the Pebble app relaunches it for a widget tap)
function trackingCommand(key, wantTracking, attempt) {
  var dict = {};
  dict[key] = 1;
  sendToWatch(dict, function() {}, function(err) {
    console.log("SleepSense PKJS: Could not send " + key + ": " + JSON.stringify(err));
  });
  setTimeout(function() {
    if (isTracking !== wantTracking && attempt < 3) trackingCommand(key, wantTracking, attempt + 1);
  }, 6000);
}

// Held open by the app until the widget is tapped, so a command arrives within a second
function listenForCommands() {
  phoneAlarm.waitForCommand(function(cmd) {
    if (cmd === "stop" && isTracking) {
      console.log("SleepSense PKJS: Stop tracking requested from the phone");
      trackingCommand("COMMAND_STOP_TRACKING", false, 0);
    }
    if (cmd === "start" && !isTracking) {
      console.log("SleepSense PKJS: Start tracking requested from the phone");
      trackingCommand("COMMAND_START_TRACKING", true, 0);
    }
    // No app (null) or nothing pending ("none"): ask again, after a pause if the app isn't there
    setTimeout(listenForCommands, cmd === null ? 15000 : 100);
  });
}

// ---- Phone alarm sync (needs the SleepSense Android companion app) ----
// The phone drives: when its next alarm changes, or the watch shows a different time, the
// watch is told the new time (or to turn the smart alarm off when the phone has no alarm).
function syncPhoneAlarm() {
  phoneAlarm.fetchPhoneAlarm(function(a) {
    if (!a || !a.sync) return;
    var key = a.enabled ? (a.hour + ":" + a.min) : "none";
    // The Android app counts every alarm change, so even a quick off/on (or re-enabling at the
    // same time) is seen as a change here
    var revChanged = a.rev !== undefined && String(a.rev) !== localStorage.getItem("phone_alarm_rev");
    var watchOn = localStorage.getItem("alarm_en") !== "false";
    var differs = a.enabled
      ? (parseInt(localStorage.getItem("alarm_hour"), 10) !== a.hour ||
         parseInt(localStorage.getItem("alarm_min"), 10) !== a.min)
      : watchOn; // the phone has no alarm: the watch alarm is turned off to match
    console.log("SleepSense PKJS: phone alarm " + key + " rev " + a.rev + " (last " +
      localStorage.getItem("phone_alarm_last") + "/" + localStorage.getItem("phone_alarm_rev") +
      ") watchOn=" + watchOn + " differs=" + differs);
    if (key === localStorage.getItem("phone_alarm_last") && !differs && !revChanged) return;

    var dict = { SMART_ALARM_ENABLED: a.enabled ? 1 : 0 };
    if (a.enabled) {
      dict.ALARM_TARGET_HOUR = a.hour;
      dict.ALARM_TARGET_MIN = a.min;
    }
    sendToWatch(dict, function() {
      console.log("SleepSense PKJS: Phone alarm " + key + " sent to watch");
      localStorage.setItem("phone_alarm_last", key);
      if (a.rev !== undefined) localStorage.setItem("phone_alarm_rev", a.rev);
      // The watch confirms in its next status message; keep the settings page right meanwhile
      localStorage.setItem("alarm_en", a.enabled);
      if (a.enabled) {
        localStorage.setItem("alarm_hour", a.hour);
        localStorage.setItem("alarm_min", a.min);
      }
    }, function(err) {
      console.log("SleepSense PKJS: Could not send phone alarm: " + JSON.stringify(err));
    });
  }, trackingStatus());
}

Pebble.addEventListener("ready", function(e) {
  console.log("SleepSense PKJS: Ready");

  // First check after the startup message above has gone out, then every minute while open
  setTimeout(syncPhoneAlarm, 4000);
  listenForCommands();
  setTimeout(function() { pushHistory(true); }, 8000);
  setInterval(syncPhoneAlarm, 60000);

  // Send saved alarm settings to watch if present
  var savedAlarmHour = localStorage.getItem("alarm_hour");
  var savedAlarmMin = localStorage.getItem("alarm_min");
  var savedSmartWin = localStorage.getItem("smart_win");
  var savedAlarmEn = localStorage.getItem("alarm_en");

  // One message: sensor toggles always, alarm settings when saved
  var dict = sensorDict();
  dict.SNOOZE_MINUTES = snoozeMinutes();
  if (savedAlarmHour !== null && savedAlarmMin !== null) {
    dict.ALARM_TARGET_HOUR = parseInt(savedAlarmHour, 10);
    dict.ALARM_TARGET_MIN = parseInt(savedAlarmMin, 10);
    dict.SMART_WINDOW_MIN = savedSmartWin ? parseInt(savedSmartWin, 10) : 30;
    dict.SMART_ALARM_ENABLED = (savedAlarmEn === "false") ? 0 : 1;
  }
  sendToWatch(dict, function() {
    console.log("SleepSense PKJS: Restored saved settings to watch");
  }, function(err) {
    console.log("SleepSense PKJS: Failed to restore settings: " + JSON.stringify(err));
  });
});

Pebble.addEventListener("appmessage", function(e) {
  var dict = e.payload;
  console.log("SleepSense PKJS: Received message: " + JSON.stringify(dict));

  if (dict.TRACKING_ACTIVE !== undefined) {
    sleepHistory.record(dict.TRACKING_ACTIVE === 1, dict, Date.now());
    pushHistory(dict.TRACKING_ACTIVE === 0);
    var wasTracking = isTracking;
    isTracking = (dict.TRACKING_ACTIVE === 1);
    if (wasTracking !== isTracking) reportStatus();
    if (isTracking && sensorEnabled("mic_en")) {
      startSoundMonitoring();
    } else {
      stopSoundMonitoring();
    }
  }

  // The watch owns the alarm settings (a phone companion app may change them there),
  // so keep this side's copy, which feeds the settings page and the startup restore, in step.
  if (dict.ALARM_TARGET_HOUR !== undefined && dict.SMART_ALARM_ENABLED !== undefined) {
    localStorage.setItem("alarm_hour", dict.ALARM_TARGET_HOUR);
    localStorage.setItem("alarm_min", dict.ALARM_TARGET_MIN);
    localStorage.setItem("smart_win", dict.SMART_WINDOW_MIN);
    localStorage.setItem("alarm_en", dict.SMART_ALARM_ENABLED !== 0);
    localStorage.setItem("snooze_min", dict.SNOOZE_MINUTES);
  }

  if (dict.STATUS_STATE !== undefined) {
    var stageStr = stageNames[dict.STATUS_STATE] || "UNKNOWN";
    var lightStr = lightNames[dict.STATUS_LIGHT_LEVEL] || "Unknown";
    console.log("SleepSense PKJS: Current State=" + stageStr + 
                " | Duration=" + dict.STATUS_DURATION + "m" +
                " | Deep=" + dict.STATUS_DEEP_DURATION + "m" +
                " | Light=" + lightStr + 
                " | Sound=" + dict.STATUS_SOUND_LEVEL + "dB" +
                " | Cycles=" + dict.STATUS_CYCLE_COUNT +
                " | Score=" + dict.STATUS_SLEEP_SCORE + "%");
  }

  // Voice Dream Journal Note transcript received from Pebble microphone
  if (dict.VOICE_NOTE_TRANSCRIPT) {
    console.log("SleepSense PKJS: Received Voice Dream Note: '" + dict.VOICE_NOTE_TRANSCRIPT + "'");
    var timestamp = new Date().toISOString();
    var dreamEntry = {
      timestamp: timestamp,
      text: dict.VOICE_NOTE_TRANSCRIPT
    };
    var dreamLogs = [];
    try {
      dreamLogs = JSON.parse(localStorage.getItem("dream_logs") || "[]");
    } catch(err) {
      dreamLogs = [];
    }
    dreamLogs.push(dreamEntry);
    localStorage.setItem("dream_logs", JSON.stringify(dreamLogs));
  }
});

// Settings page: alarm, sensors, session graphs and export
Pebble.addEventListener("showConfiguration", function() {
  Pebble.openURL(configPage.buildConfigPageUrl({
    alarm: {
      enabled: localStorage.getItem("alarm_en") !== "false",
      hour: parseInt(localStorage.getItem("alarm_hour") || "7", 10),
      min: parseInt(localStorage.getItem("alarm_min") || "0", 10),
      window: parseInt(localStorage.getItem("smart_win") || "30", 10),
      snooze: snoozeMinutes()
    },
    sensors: {
      light: sensorEnabled("light_en"),
      mic: sensorEnabled("mic_en"),
      hr: sensorEnabled("hr_en")
    },
    sessions: sleepHistory.getBucketedSessions(),
    lastExport: sleepHistory.getLastExport(),
    now: Date.now(),
    appVersion: APP_VERSION
  }));
});

Pebble.addEventListener("webviewclosed", function(e) {
  if (!e.response) return;
  var config;
  try {
    config = JSON.parse(decodeURIComponent(e.response));
  } catch (err) {
    console.log("SleepSense PKJS: Could not parse settings response: " + err.message);
    return;
  }

  // Copy/Download marks an export even if the settings themselves are cancelled
  if (config.exportedAt) {
    sleepHistory.setLastExport(config.exportedAt);
  }
  if (config.cancelled) return;
  console.log("SleepSense PKJS: Configuration received: " + JSON.stringify(config));

  localStorage.setItem("alarm_hour", config.alarm_hour);
  localStorage.setItem("alarm_min", config.alarm_min);
  localStorage.setItem("smart_win", config.smart_win);
  localStorage.setItem("snooze_min", config.snooze_min);
  localStorage.setItem("alarm_en", config.alarm_en);
  localStorage.setItem("light_en", config.light_en);
  localStorage.setItem("mic_en", config.mic_en);
  localStorage.setItem("hr_en", config.hr_en);

  // Mic toggle takes effect immediately for a running session
  if (isTracking && config.mic_en) {
    startSoundMonitoring();
  } else if (!config.mic_en) {
    stopSoundMonitoring();
  }

  var dict = {
    ALARM_TARGET_HOUR: config.alarm_hour,
    ALARM_TARGET_MIN: config.alarm_min,
    SMART_WINDOW_MIN: config.smart_win,
    SMART_ALARM_ENABLED: config.alarm_en ? 1 : 0,
    SNOOZE_MINUTES: config.snooze_min
  };
  var sensors = sensorDict();
  for (var k in sensors) dict[k] = sensors[k];

  sendToWatch(dict, function() {
    console.log("SleepSense PKJS: Sent configuration to watch");
  }, function(err) {
    console.log("SleepSense PKJS: Error sending configuration to watch: " + JSON.stringify(err));
  });
});
