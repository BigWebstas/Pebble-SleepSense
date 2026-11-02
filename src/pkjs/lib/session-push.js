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

// One chest test chunk (the watch's bytes) as hex, for the app's chest.csv
function pushChest(bytes) {
  var hex = '';
  for (var i = 0; i < bytes.length; i++) hex += ('0' + (bytes[i] & 0xff).toString(16)).slice(-2);
  var xhr = new XMLHttpRequest();
  xhr.open('POST', 'http://127.0.0.1:8765/chest', true);
  xhr.timeout = 10000;
  xhr.send(hex);
}

module.exports = {
  pushSessions: pushSessions,
  pushChest: pushChest
};
