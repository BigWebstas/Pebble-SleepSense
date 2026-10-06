// PebbleKit JS Companion for SleepSense

var soundInterval = null;
var isTracking = false;

var stageNames = ["AWAKE", "LIGHT", "DEEP", "REM"];
var lightNames = ["Unknown", "Very Dark", "Dark", "Light", "Very Light"];

// Send periodic simulated/sampled ambient sound levels to watch during tracking
function startSoundMonitoring() {
  if (soundInterval) return;
  console.log("SleepSense PKJS: Starting phone sound monitoring");

  // Send a sound sample every 60 seconds matching the epoch window
  soundInterval = setInterval(function() {
    if (!isTracking) {
      stopSoundMonitoring();
      return;
    }

    // In a nighttime room, baseline noise is 20-35 dB with occasional snore/movement peaks
    var baseSound = 25 + Math.floor(Math.random() * 15);
    // Occasional disturbance / snore simulation (5% chance)
    if (Math.random() < 0.05) {
      baseSound += 35;
    }

    var dict = {
      PHONE_SOUND_SAMPLE: baseSound
    };

    Pebble.sendAppMessage(dict, function() {
      console.log("SleepSense PKJS: Sound sample sent: " + baseSound + " dB");
    }, function(e) {
      console.log("SleepSense PKJS: Error sending sound sample: " + JSON.stringify(e));
    });
  }, 60000);
}

function stopSoundMonitoring() {
  if (soundInterval) {
    clearInterval(soundInterval);
    soundInterval = null;
    console.log("SleepSense PKJS: Stopped phone sound monitoring");
  }
}

Pebble.addEventListener("ready", function(e) {
  console.log("SleepSense PKJS: Ready");
  
  // Send saved alarm settings to watch if present
  var savedAlarmHour = localStorage.getItem("alarm_hour");
  var savedAlarmMin = localStorage.getItem("alarm_min");
  var savedSmartWin = localStorage.getItem("smart_win");
  var savedAlarmEn = localStorage.getItem("alarm_en");

  if (savedAlarmHour !== null && savedAlarmMin !== null) {
    var dict = {
      ALARM_TARGET_HOUR: parseInt(savedAlarmHour, 10),
      ALARM_TARGET_MIN: parseInt(savedAlarmMin, 10),
      SMART_WINDOW_MIN: savedSmartWin ? parseInt(savedSmartWin, 10) : 30,
      SMART_ALARM_ENABLED: (savedAlarmEn === "false") ? 0 : 1
    };
    Pebble.sendAppMessage(dict, function() {
      console.log("SleepSense PKJS: Restored saved alarm configuration to watch");
    }, function(err) {
      console.log("SleepSense PKJS: Failed to restore alarm config: " + JSON.stringify(err));
    });
  }
});

Pebble.addEventListener("appmessage", function(e) {
  var dict = e.payload;
  console.log("SleepSense PKJS: Received message: " + JSON.stringify(dict));

  if (dict.TRACKING_ACTIVE !== undefined) {
    isTracking = (dict.TRACKING_ACTIVE === 1);
    if (isTracking) {
      startSoundMonitoring();
    } else {
      stopSoundMonitoring();
    }
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

// Settings configuration UI
Pebble.addEventListener("showConfiguration", function() {
  var alarmHour = localStorage.getItem("alarm_hour") || 7;
  var alarmMin = localStorage.getItem("alarm_min") || 0;
  var smartWin = localStorage.getItem("smart_win") || 30;
  var alarmEn = localStorage.getItem("alarm_en") !== "false";

  // Data URI configuration page
  var html = '<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">' +
    '<title>SleepSense Configuration</title>' +
    '<style>' +
    'body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; padding: 20px; background: #0c1a2d; color: #fff; }' +
    'h2 { color: #00d2d3; margin-top: 0; }' +
    '.card { background: #162a45; padding: 15px; border-radius: 8px; margin-bottom: 16px; }' +
    'label { display: block; margin: 10px 0 5px; font-weight: bold; }' +
    'input, select { width: 100%; padding: 10px; border-radius: 5px; border: 1px solid #32527b; background: #0c1a2d; color: #fff; font-size: 16px; box-sizing: border-box; }' +
    'button { width: 100%; padding: 12px; background: #00d2d3; color: #0c1a2d; font-size: 18px; font-weight: bold; border: none; border-radius: 6px; cursor: pointer; margin-top: 10px; }' +
    '</style></head><body>' +
    '<h2>SleepSense Settings</h2>' +
    '<div class="card">' +
    '<h3>Smart Wake Alarm</h3>' +
    '<label>Enable Smart Alarm</label>' +
    '<select id="alarm_en"><option value="1"' + (alarmEn ? ' selected' : '') + '>Enabled</option><option value="0"' + (!alarmEn ? ' selected' : '') + '>Disabled</option></select>' +
    '<label>Target Alarm Time (Hour 0-23)</label>' +
    '<input type="number" id="alarm_hour" min="0" max="23" value="' + alarmHour + '">' +
    '<label>Target Alarm Time (Minute 0-59)</label>' +
    '<input type="number" id="alarm_min" min="0" max="59" value="' + alarmMin + '">' +
    '<label>Smart Wake Window</label>' +
    '<select id="smart_win">' +
    '<option value="15"' + (smartWin == 15 ? ' selected' : '') + '>15 Minutes</option>' +
    '<option value="30"' + (smartWin == 30 ? ' selected' : '') + '>30 Minutes</option>' +
    '<option value="45"' + (smartWin == 45 ? ' selected' : '') + '>45 Minutes</option>' +
    '</select>' +
    '</div>' +
    '<button id="save_btn">Save Settings</button>' +
    '<script>' +
    'document.getElementById("save_btn").onclick = function() {' +
    '  var config = {' +
    '    alarm_hour: parseInt(document.getElementById("alarm_hour").value, 10),' +
    '    alarm_min: parseInt(document.getElementById("alarm_min").value, 10),' +
    '    smart_win: parseInt(document.getElementById("smart_win").value, 10),' +
    '    alarm_en: (document.getElementById("alarm_en").value === "1")' +
    '  };' +
    '  location.href = "pebblejs://close#" + encodeURIComponent(JSON.stringify(config));' +
    '};' +
    '</script></body></html>';

  Pebble.openURL("data:text/html;base64," + btoa(html));
});

Pebble.addEventListener("webviewclosed", function(e) {
  if (!e.response) return;
  var config = JSON.parse(decodeURIComponent(e.response));
  console.log("SleepSense PKJS: Configuration received: " + JSON.stringify(config));

  localStorage.setItem("alarm_hour", config.alarm_hour);
  localStorage.setItem("alarm_min", config.alarm_min);
  localStorage.setItem("smart_win", config.smart_win);
  localStorage.setItem("alarm_en", config.alarm_en);

  var dict = {
    ALARM_TARGET_HOUR: config.alarm_hour,
    ALARM_TARGET_MIN: config.alarm_min,
    SMART_WINDOW_MIN: config.smart_win,
    SMART_ALARM_ENABLED: config.alarm_en ? 1 : 0
  };

  Pebble.sendAppMessage(dict, function() {
    console.log("SleepSense PKJS: Sent configuration to watch");
  }, function(err) {
    console.log("SleepSense PKJS: Error sending configuration to watch: " + JSON.stringify(err));
  });
});
