// A canvas double for Node tests of the 3D adapters: procedural textures draw into it without a
// browser. Only what the texture code needs is faked (2D context calls are no-ops).

function fakeContext() {
  const noop = () => {};
  const image = (w, h) => ({ width: w, height: h, data: new Uint8ClampedArray(w * h * 4) });
  const special = {
    createImageData: image,
    getImageData: (_x, _y, w, h) => image(w, h),
    createLinearGradient: () => ({ addColorStop: noop }),
    createRadialGradient: () => ({ addColorStop: noop }),
    measureText: () => ({ width: 10 }),
  };
  return new Proxy(
    {},
    {
      get: (_target, key) => special[key] ?? noop,
      set: () => true,
    },
  );
}

class FakeOffscreenCanvas {
  constructor(width, height) {
    this.width = width;
    this.height = height;
    this.context = fakeContext();
  }

  getContext() {
    return this.context;
  }
}

/** Installs the double as OffscreenCanvas (the adapters use it when there is no document). */
export function installFakeCanvas() {
  globalThis.OffscreenCanvas = FakeOffscreenCanvas;
}
