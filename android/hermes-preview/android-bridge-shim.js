(() => {
  const noop = () => {};
  const subscription = () => noop;
  const asyncNull = async () => null;
  const asyncEmpty = async () => ({});

  const rootOverrides = {
    glassSupported: false,
    translucencySupported: false,
    localModelsEnabled: false,
    guestOnboardingEnabled: false,
    skipIntro: true,
    getConnection: asyncNull,
    getConnectionFor: asyncNull,
    revalidateConnection: asyncNull,
    getProfileRoutes: async () => [],
    getPoolLimits: asyncEmpty,
    getGatewayWsUrl: asyncNull,
    getGatewayWsUrlFor: asyncNull,
    getAgentRoster: async () => [],
    claimAmbientCue: async () => false,
    onBrowserPopoutClosed: subscription,
  };

  const makeProxy = (path = []) => new Proxy(function () {}, {
    get(_target, prop) {
      if (prop === 'then') return undefined;
      if (path.length === 0 && Object.prototype.hasOwnProperty.call(rootOverrides, prop)) {
        return rootOverrides[prop];
      }
      if (prop === Symbol.toPrimitive) return () => false;
      if (prop === 'toJSON') return () => null;
      return makeProxy([...path, String(prop)]);
    },
    apply() {
      const name = path[path.length - 1] || '';
      if (name.startsWith('on') || name.startsWith('subscribe')) return noop;
      if (name.startsWith('is') || name.startsWith('has')) return false;
      if (name.startsWith('get') || name.startsWith('set') || name.startsWith('open') || name.startsWith('close') || name.startsWith('touch') || name.startsWith('revalidate') || name.startsWith('claim')) {
        return Promise.resolve(null);
      }
      return null;
    }
  });

  if (!window.hermesDesktop) {
    window.hermesDesktop = makeProxy();
  }
  window.__HERMES_ANDROID_PREVIEW__ = true;

  // Preview-only crash surface. If the real Hermes renderer throws before it can
  // paint, show the actual browser error instead of leaving a featureless black screen.
  const showFatal = (title, detail) => {
    if (document.getElementById('hermes-android-fatal')) return;
    const panel = document.createElement('div');
    panel.id = 'hermes-android-fatal';
    panel.style.cssText = 'position:fixed;inset:20px;z-index:2147483647;padding:18px;border-radius:14px;background:#1a0f14;color:#ffe7ef;font:14px/1.45 system-ui,sans-serif;overflow:auto;border:1px solid #74334a;white-space:pre-wrap';
    panel.textContent = title + '\n\n' + detail;
    const mount = () => (document.body || document.documentElement).appendChild(panel);
    if (document.body) mount(); else window.addEventListener('DOMContentLoaded', mount, { once: true });
  };

  window.addEventListener('error', (event) => {
    const detail = event.error?.stack || event.message || 'Unknown JavaScript error';
    showFatal('Hermes Android preview — JavaScript error', String(detail));
  });
  window.addEventListener('unhandledrejection', (event) => {
    const reason = event.reason;
    const detail = reason?.stack || reason?.message || String(reason || 'Unhandled promise rejection');
    showFatal('Hermes Android preview — startup error', String(detail));
  });
})();
