(function () {
  if (window.CineCodeMiniApp) return;
  const native = window.__CineCodeMiniAppNative;

  window.CineCodeMiniApp = {
    version: "1.0.0",
    toast(message) {
      if (native && native.toast) native.toast(String(message || ""));
    },
    storage: {
      get(key, fallback = null) {
        try {
          if (native && native.storageGet) {
            const raw = native.storageGet(String(key));
            return raw === null || raw === "" ? fallback : JSON.parse(raw);
          }
          const v = localStorage.getItem("cc_mini_" + key);
          return v === null ? fallback : JSON.parse(v);
        } catch (e) {
          return fallback;
        }
      },
      set(key, value) {
        const serialized = JSON.stringify(value);
        if (native && native.storageSet) {
          native.storageSet(String(key), serialized);
        } else {
          localStorage.setItem("cc_mini_" + key, serialized);
        }
      },
      remove(key) {
        if (native && native.storageRemove) native.storageRemove(String(key));
        else localStorage.removeItem("cc_mini_" + key);
      }
    },
    clipboard: {
      copy(text) {
        if (native && native.copyToClipboard) native.copyToClipboard(String(text || ""));
      }
    },
    getInfo() {
      try {
        return native && native.getAppInfo ? JSON.parse(native.getAppInfo()) : { app: "CineCode-AI" };
      } catch (e) {
        return { app: "CineCode-AI" };
      }
    }
  };
})();
