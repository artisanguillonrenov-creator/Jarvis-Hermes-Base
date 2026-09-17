(() => {
  const noop = () => {};
  const subscription = () => noop;
  const asyncNull = async () => null;
  const asyncEmpty = async () => ({});

  // The Desktop renderer expects Electron's preload bridge. Android preview
  // deliberately has no backend yet, so return valid *shapes* for read-only
  // startup calls instead of null. This lets the genuine Hermes UI paint while
  // keeping backend-dependent controls inert.
  const previewApi = async (request = {}) => {
    const path = String(request?.path || '');
    const pathname = path.split('?')[0];

    if (pathname === '/api/config' || pathname === '/api/config/defaults') return {};
    if (pathname === '/api/profiles') return { profiles: [] };
    if (pathname === '/api/profiles/active') return { active: 'default', current: 'default' };
    if (pathname === '/api/sessions') return { sessions: [], total: 0, limit: 0, offset: 0 };
    if (pathname === '/api/status') return { ok: true, status: 'preview' };
    if (pathname === '/api/logs') return { logs: [], files: [] };
    if (pathname === '/api/projects') return { projects: [] };
    if (pathname === '/api/cron/jobs') return { jobs: [] };
    if (pathname === '/api/model/options') return { models: [], providers: [] };
    if (pathname === '/api/model/info') return { provider: '', model: '' };
    if (pathname === '/api/model/auxiliary') return { models: {} };
    if (pathname === '/api/tools/toolsets') return { toolsets: [] };
    if (pathname === '/api/skills') return { skills: [] };
    if (pathname === '/api/mcp') return { servers: [] };
    if (pathname === '/api/messaging/platforms') return { platforms: [] };
    if ((request?.method || 'GET').toUpperCase() !== 'GET') return { ok: true };

    return {};
  };

  const rootOverrides = {
    glassSupported: false,
    translucencySupported: false,
    localModelsEnabled: false,
    guestOnboardingEnabled: false,
    skipIntro: true,
    api: previewApi,
    getConnection: asyncNull,
    getConnectionFor: asyncNull,
    revalidateConnection: async () => ({ ok: true, rebuilt: false }),
    getProfileRoutes: async () => [],
    getPoolLimits: async () => ({ maxBackends: 1, idleMs: 0 }),
    getGatewayWsUrl: asyncNull,
    getGatewayWsUrlFor: asyncNull,
    getAgentRoster: async () => [],
    getMachineProfile: async () => ({ locale: navigator.language || 'en' }),
    claimAmbientCue: async () => false,
    onBrowserPopoutClosed: subscription,
  };

  const makeProxy = (path = []) => new Proxy(function () {}, {
    get(_target, prop) {
      // Prevent Promise assimilation of proxy namespaces.
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

      // Electron event/listener APIs must return an unsubscribe function because
      // React effects call that value during cleanup.
      if (
        name.startsWith('on') ||
        name.startsWith('subscribe') ||
        name.startsWith('watch') ||
        name.startsWith('listen') ||
        name.startsWith('observe') ||
        name.startsWith('register')
      ) return noop;

      if (name.startsWith('is') || name.startsWith('has')) return false;
      if (
        name.startsWith('get') ||
        name.startsWith('set') ||
        name.startsWith('open') ||
        name.startsWith('close') ||
        name.startsWith('touch') ||
        name.startsWith('revalidate') ||
        name.startsWith('claim') ||
        name.startsWith('save') ||
        name.startsWith('apply') ||
        name.startsWith('test') ||
        name.startsWith('request')
      ) return Promise.resolve(null);

      // Unknown bridge commands are inert in the visual preview, but return a
      // Promise-compatible empty result rather than null so callers can safely
      // chain .then/.catch/.finally.
      return Promise.resolve({});
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
