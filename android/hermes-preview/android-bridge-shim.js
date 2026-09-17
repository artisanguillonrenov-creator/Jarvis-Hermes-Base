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
})();
