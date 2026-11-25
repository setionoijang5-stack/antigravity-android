/**
 * core/notifications-bridge.js - exposes window.nativeNotifications backed by
 * Android's NotificationBridge (com.agy.bridge.NotificationBridge).
 *
 * Contract matched against main.js NativeNotificationService (fJd):
 *   - send({ id, title, body, silent, payload: { cascadeId, workspaceUri, type } })
 *   - onClicked(callback)  -> callback({ cascadeId })
 *   - openSystemPreferences()
 * Optional extensions (wired via patch_main.py):
 *   - requestPermission()  -> Promise<boolean | undefined>
 *   - clear(cascadeId)     -> Promise<void>
 */

var notificationClickListeners = [];
var pendingNotificationClicks = [];
var notificationPermCallbacks = {};

function dispatchNotificationClick(payload) {
  if (!payload || !payload.cascadeId) return;
  if (notificationClickListeners.length === 0) {
    pendingNotificationClicks.push(payload);
    return;
  }
  var snapshot = notificationClickListeners.slice();
  for (var i = 0; i < snapshot.length; i++) {
    try {
      snapshot[i](payload);
    } catch (e) {
      warn('notifications.onClicked', e);
    }
  }
}

global.__agOnNotificationClicked = function(payload) {
  try {
    var parsed = typeof payload === 'string' ? JSON.parse(payload) : payload;
    if (parsed && typeof parsed.cascadeId === 'string' && parsed.cascadeId) {
      dispatchNotificationClick({ cascadeId: parsed.cascadeId });
    }
  } catch (e) {
    warn('notifications.dispatch', e);
  }
};

global.__agResolveNotificationPermission = function(id, status) {
  var fn = notificationPermCallbacks[id];
  if (fn) {
    delete notificationPermCallbacks[id];
    try {
      fn(status);
    } catch (e) {
      warn('notifications.permCallback', e);
    }
  }
};

global.nativeNotifications = {
  send: function(options) {
    try {
      if (!options || typeof options !== 'object') {
        return Promise.resolve(false);
      }
      var title = options.title ? String(options.title) : 'Antigravity';
      var body = options.body ? String(options.body) : '';
      var tag = options.id ? String(options.id) : ('agy_agent_' + Date.now());
      var cascadeId = (options.payload && options.payload.cascadeId)
        ? String(options.payload.cascadeId)
        : '';
      var silent = !!options.silent;

      if (global.Android && typeof global.Android.postAgentNotification === 'function') {
        return Promise.resolve(!!global.Android.postAgentNotification(title, body, tag, cascadeId, silent));
      }
      if (global.Android && typeof global.Android.postNotification === 'function') {
        return Promise.resolve(!!global.Android.postNotification(title, body, tag));
      }
    } catch (e) {
      warn('notifications.send', e);
    }
    return Promise.resolve(false);
  },

  onClicked: function(callback) {
    if (typeof callback !== 'function') {
      return function() {};
    }
    notificationClickListeners.push(callback);
    if (pendingNotificationClicks.length > 0) {
      var queued = pendingNotificationClicks.splice(0, pendingNotificationClicks.length);
      Promise.resolve().then(function() {
        for (var i = 0; i < queued.length; i++) {
          try {
            callback(queued[i]);
          } catch (e) {
            warn('notifications.replay', e);
          }
        }
      });
    }
    return function() {
      var idx = notificationClickListeners.indexOf(callback);
      if (idx >= 0) {
        notificationClickListeners.splice(idx, 1);
      }
    };
  },

  openSystemPreferences: function() {
    try {
      if (global.Android && typeof global.Android.openNotificationSettings === 'function') {
        global.Android.openNotificationSettings();
      } else if (global.Android && typeof global.Android.openAppSettings === 'function') {
        global.Android.openAppSettings();
      }
    } catch (e) {
      warn('notifications.openSystemPreferences', e);
    }
    return Promise.resolve();
  },

  requestPermission: function() {
    return new Promise(function(resolve) {
      try {
        if (!global.Android || typeof global.Android.getNotificationPermissionStatus !== 'function') {
          resolve(undefined);
          return;
        }
        var current = global.Android.getNotificationPermissionStatus();
        if (current === 'granted') {
          resolve(true);
          return;
        }
        if (typeof global.Android.requestNotificationPermission === 'function' && current === 'not-determined') {
          var id = 'notif_' + Date.now() + '_' + Math.random().toString(36).slice(2);
          var settled = false;
          var timer = setTimeout(function() {
            if (!settled) {
              settled = true;
              delete notificationPermCallbacks[id];
              resolve(undefined);
            }
          }, 30000);
          notificationPermCallbacks[id] = function(st) {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            if (st === 'granted') {
              resolve(true);
            } else {
              // If user permanently denied, Android resolves immediately without a dialog.
              // Open Android notification settings so the user can re-enable them.
              resolve(false);
            }
          };
          global.Android.requestNotificationPermission(id);
          return;
        }
        // OS-level notifications are disabled (API < 33 or blocked): open settings
        if (typeof global.Android.openNotificationSettings === 'function') {
          global.Android.openNotificationSettings();
        }
        resolve(false);
      } catch (e) {
        warn('notifications.requestPermission', e);
        resolve(undefined);
      }
    });
  },

  clear: function(cascadeId) {
    try {
      if (cascadeId && global.Android && typeof global.Android.cancelCascadeNotifications === 'function') {
        global.Android.cancelCascadeNotifications(String(cascadeId));
      }
    } catch (e) {
      warn('notifications.clear', e);
    }
    return Promise.resolve();
  }
};
