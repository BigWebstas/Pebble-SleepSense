// Asks the SleepSense Android companion app (if installed) for the phone's next alarm.
// The app serves it on the loopback address; with no app this just fails quietly.
'use strict';

var URL = 'http://127.0.0.1:8765/alarm';

// Calls back with {sync, enabled, hour, min}, or null if the app isn't there
function fetchPhoneAlarm(callback) {
  var xhr = new XMLHttpRequest();
  xhr.open('GET', URL, true);
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

module.exports = {
  fetchPhoneAlarm: fetchPhoneAlarm
};
