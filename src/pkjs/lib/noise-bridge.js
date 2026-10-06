// Talks to the SleepSense Android companion app's noise monitor (phone microphone).
// Calls back with the parsed JSON reply, or null if the app isn't there.
'use strict';

function get(path, callback) {
  var xhr = new XMLHttpRequest();
  xhr.open('GET', 'http://127.0.0.1:8765' + path, true);
  xhr.timeout = 6000;
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
  start: function (cb) { get('/noise/start', cb || function () {}); },
  stop: function (cb) { get('/noise/stop', cb || function () {}); },
  status: function (cb) { get('/noise', cb); }
};
