// Sends the sleep history to the SleepSense Android companion app (if installed), which shows
// the graphs and the export. Fails quietly when the app isn't there.
'use strict';

var URL = 'http://127.0.0.1:8765/sessions';

function pushSessions(sessions, callback) {
  var xhr = new XMLHttpRequest();
  xhr.open('POST', URL, true);
  xhr.timeout = 10000;
  xhr.setRequestHeader('Content-Type', 'application/json');
  xhr.onload = function () { callback && callback(xhr.status === 200); };
  xhr.onerror = function () { callback && callback(false); };
  xhr.ontimeout = function () { callback && callback(false); };
  xhr.send(JSON.stringify(sessions));
}

module.exports = {
  pushSessions: pushSessions
};
