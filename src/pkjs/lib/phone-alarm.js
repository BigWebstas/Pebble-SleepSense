// Asks the SleepSense Android companion app (if installed) for the phone's next alarm.
// The app serves it on the loopback address; with no app this just fails quietly.
'use strict';

var URL = 'http://127.0.0.1:8765/alarm';

// Calls back with {sync, enabled, hour, min}, or null if the app isn't there
function fetchPhoneAlarm(callback, status) {
  var xhr = new XMLHttpRequest();
  // The request doubles as a heartbeat; `status` tells the app whether the watch is tracking
  xhr.open('GET', status ? URL + '?tracking=' + (status.tracking ? 1 : 0) + '&since=' + status.since : URL, true);
  xhr.timeout = 4000;
  xhr.onload = function () {
    try {
      callback(JSON.parse(xhr.responseText));
    } catch (err) {
      callback(null);
    }
  };
  xhr.onerror = function () { callback(null); };
  xhr.ontimeout = function () { callback(null); };
  xhr.send();
}

// Waits (up to ~25 s, held open by the app) for a command from the phone: {cmd: "start" | "none"}
function waitForCommand(callback) {
  var xhr = new XMLHttpRequest();
  xhr.open('GET', 'http://127.0.0.1:8765/command', true);
  xhr.timeout = 35000;
  xhr.onload = function () {
    try {
      callback(JSON.parse(xhr.responseText).cmd);
    } catch (err) {
      callback(null);
    }
  };
  xhr.onerror = function () { callback(null); };
  xhr.ontimeout = function () { callback(null); };
  xhr.send();
}

module.exports = {
  fetchPhoneAlarm: fetchPhoneAlarm,
  waitForCommand: waitForCommand
};
