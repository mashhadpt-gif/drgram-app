#extension GL_OES_EGL_image_external : require

precision lowp float;

varying vec2 uv;
varying vec2 auv;
uniform samplerExternalOES sTexture;
uniform vec2 pixelWH;
uniform float roundRadius;
uniform float scale;
uniform float alpha;
uniform float shapeFrom, shapeTo, shapeT;
uniform float dual;
uniform float blur;
uniform float beautify;
// DrGram Fix 41: full beauty suite + Instagram-style camera FX
uniform float fxNose;        // 0..1 nose slimming
uniform float fxTeeth;       // 0..1 teeth whitening
uniform float fxEyes;        // 0..1 conservative eye enlargement
uniform float fxJaw;         // 0..1 lower-face slimming
uniform float fxFx;          // selected camera effect id (0 = none)
uniform float fxFaceCount;   // 0..2
uniform vec4 fxFaces[2];     // xy = face center (uv), zw = half size (uv)
uniform vec2 fxNoses[2];     // nose anchor (uv)
uniform vec2 fxMouths[2];    // mouth anchor (uv)
// DrGram Fix 46: standalone makeup suite + real eye landmarks
uniform float fxBlush;       // 0..1 cheek tint
uniform float fxLips;        // 0..1 lip tint
uniform float fxBright;      // 0..1 skin brightening
uniform vec2 fxEyesL[2];     // real left-eye anchor (uv) per face (ML Kit)
uniform vec2 fxEyesR[2];     // real right-eye anchor (uv) per face (ML Kit)
uniform float fxEyePrecise;  // 1.0 when real eye anchors are valid

float modI(float a,float b) {
  return floor(a-floor((a+0.5)/b)*b+0.5);
}
bool eq(float a, float b) {
  return abs(a - b) < .1;
}
bool eq(float a, float b, float b2) {
  return abs(a - b) < .1 || abs(a - b2) < .1;
}
float box(vec2 position, vec2 halfSize, float cornerRadius) {
  position = abs(position) - halfSize + cornerRadius;
  return length(max(position, 0.0)) + min(max(position.x, position.y), 0.0) - cornerRadius;
}
float star(in vec2 p, in float r) {
  const vec2  acs = vec2(.9659258, .258819);
  const vec2  ecs = vec2(.8090169, .5877852);
  float bn = mod(atan(p.x,p.y),.52359876)-.26179938;
  p = length(p)*vec2(cos(bn),abs(sin(bn))) - r*acs;
  p += ecs*clamp( -dot(p,ecs), 0.0, r*acs.y/ecs.y);
  return length(p)*sign(p.x);
}
float opSmoothUnion(float d1, float d2, float k) {
  float h = max(k-abs(d1-d2),0.0);
  return min(d1, d2) - h*h*0.25/k;
}
float scene() {
  vec2 p = (auv - vec2(.5)) * vec2(1., pixelWH.x / pixelWH.y);
  vec2 r = .5 * vec2(1., pixelWH.x / pixelWH.y) * scale;
  float R = min(r.x, r.y), rr = roundRadius / pixelWH.y;
  float a = modI(shapeFrom, 3.), b = modI(shapeTo, 3.);
  float boxSDF = box(
    p,
    mix(eq(a, 2.)     ? r : vec2(R), eq(b, 2.)     ? r : vec2(R), shapeT),
    mix(eq(a, 0., 3.) ? R : rr,      eq(b, 0., 3.) ? R : rr,      shapeT)
  ) * pixelWH.x;
  if (eq(3., a, b)) {
    float starSDF = opSmoothUnion(box(p, vec2(R * .78), R), star(p, R * .78), .25) * pixelWH.x;
    float starA = eq(a, 3.) ? 1. - shapeT : 0.;
    float starB = eq(b, 3.) ? shapeT : 0.;
    return mix(boxSDF, starSDF, starA + starB);
  } else {
    return boxSDF;
  }
}
vec4 makeblur() {
  vec2 S = 4. * vec2(1., pixelWH.x / pixelWH.y);
  vec2 st = fract(uv * S);
  st=st*st*(3.0-2.0*st);
  vec2 u = floor(uv * S) / S;
  return mix(
    mix(texture2D(sTexture, u + vec2(0., 0.) / S), texture2D(sTexture, u + vec2(1., 0.) / S), st.x),
    mix(texture2D(sTexture, u + vec2(0., 1.) / S), texture2D(sTexture, u + vec2(1., 1.) / S), st.x),
    st.y
  );
}
vec4 program() {
  if (scale <= 0.)
    return vec4(0.);
  float dalpha = clamp(1. - scene(), 0., 2.) / 2. * alpha;
  if (dalpha <= 0.)
    return vec4(0.);

  if (blur >= 1.)
    return makeblur();
  else if (blur <= 0.)
    return texture2D(sTexture, uv) * vec4(1., 1., 1., dalpha);
  else
    return mix(texture2D(sTexture, uv), makeblur(), blur) * vec4(1., 1., 1., dalpha);
}

// ===================== DrGram Fix 41: LuxGram camera FX =====================
// Pipeline: warp (nose slim / fun sampling fx) -> sample -> skin smooth
// (skin-masked, face-weighted bilateral) -> teeth whitening -> face glow
// -> colour grade (sunset/pastel/vintage/...). beautify == 0 && fxFx == 0 &&
// fxNose == 0 && fxTeeth == 0 is a pixel-identical early-out.

mediump float hash21(mediump vec2 p) {
  p = fract(p * vec2(443.897, 441.423));
  p += dot(p, p + 19.19);
  return fract(p.x * p.y);
}

// Soft falloff inside an ellipse (1 at centre, 0 at the rim + margin).
mediump float ellipseFalloff(mediump vec2 p, mediump vec2 c, mediump vec2 halfSize) {
  mediump vec2 q = (p - c) / max(halfSize, vec2(1e-4));
  mediump float e = length(q);
  return 1.0 - smoothstep(0.75, 1.35, e);
}

// Face weight: strongest smoothing inside detected faces; 1.0 when no faces.
mediump float faceWeight(mediump vec2 p) {
  if (fxFaceCount < 0.5) {
    return 1.0;
  }
  mediump float w = 0.0;
  for (int i = 0; i < 2; i++) {
    if (float(i) < fxFaceCount) {
      w = max(w, ellipseFalloff(p, fxFaces[i].xy, fxFaces[i].zw * 1.12));
    }
  }
  return w;
}

// Skin likelihood from chroma + luma (works across skin tones).
mediump float skinMask(mediump vec3 c) {
  mediump float mx = max(c.r, max(c.g, c.b));
  mediump float mn = min(c.r, min(c.g, c.b));
  mediump float sat = mx - mn;
  mediump float rG = c.r - c.g;
  mediump float gB = c.g - c.b;
  mediump float lum = dot(c, vec3(0.299, 0.587, 0.114));
  mediump float m = 1.0;
  m *= smoothstep(0.02, 0.09, rG);            // redder than green
  m *= 1.0 - smoothstep(0.16, 0.30, rG);      // but not sunburnt
  m *= 1.0 - smoothstep(0.10, 0.22, gB);      // not too yellow-green
  m *= smoothstep(0.12, 0.30, lum);           // not a shadow
  m *= 1.0 - smoothstep(0.55, 0.92, sat);     // not a saturated object
  return clamp(m, 0.0, 1.0);
}

// Nose slimming: horizontal pinch around each detected nose anchor.
// Sampling coordinate is pushed AWAY from the nose line horizontally, so the
// rendered image compresses toward it -> slimmer nose, natural edges.
highp vec2 drgramWarp(highp vec2 p) {
  if (fxFaceCount > 0.5) {
    for (int i = 0; i < 2; i++) {
      if (float(i) < fxFaceCount) {
        highp vec2 face = fxFaces[i].xy;
        highp vec2 hs = fxFaces[i].zw;
        if (fxNose > 0.001) {
          highp vec2 c = fxNoses[i];
          highp vec2 q = (p - c) / max(hs * vec2(0.60, 0.85), vec2(1e-4));
          highp float e = dot(q, q);
          if (e < 1.0) {
            highp float f = 1.0 - e;
            highp float squeeze = 1.0 - fxNose * 0.30 * f * f;
            p.x = c.x + (p.x - c.x) / squeeze;
          }
        }

        // DrGram Fix 46: real ML Kit eye anchors when the software detector is
        // feeding the shader; geometric estimate stays the fallback for the
        // hardware-only path, which exposes no dense landmarks.
        if (fxEyes > 0.001) {
          for (int eye = 0; eye < 2; eye++) {
            highp float side = eye == 0 ? -1.0 : 1.0;
            highp vec2 eyeCenter = face + vec2(side * hs.x * 0.27, -hs.y * 0.20);
            highp vec2 eyeHalf = hs * vec2(0.19, 0.13);
            if (fxEyePrecise > 0.5) {
              eyeCenter = eye == 0 ? fxEyesL[i] : fxEyesR[i];
              eyeHalf = hs * vec2(0.15, 0.105);
            }
            highp vec2 eyeQ = (p - eyeCenter) / max(eyeHalf, vec2(1e-4));
            highp float eyeD = dot(eyeQ, eyeQ);
            if (eyeD < 1.0) {
              highp float eyeMask = (1.0 - smoothstep(0.42, 1.0, eyeD));
              p = eyeCenter + (p - eyeCenter) * (1.0 - fxEyes * 0.16 * eyeMask);
            }
          }
        }

        // Slim only the lower half of the detected face, fading out at cheeks
        // and chin to avoid a visible pinch seam at the face rectangle boundary.
        if (fxJaw > 0.001) {
          highp float y = (p.y - (face.y + hs.y * 0.05)) / max(hs.y * 0.95, 1e-4);
          highp float vertical = smoothstep(0.05, 0.34, y) * (1.0 - smoothstep(0.88, 1.10, y));
          highp float lateral = 1.0 - smoothstep(hs.x * 0.50, hs.x * 0.98, abs(p.x - face.x));
          highp float amount = fxJaw * 0.12 * vertical * lateral;
          p.x = face.x + (p.x - face.x) / max(0.82, 1.0 - amount);
        }
      }
    }
  }
  // fun sampling effects
  if (fxFx > 9.5 && fxFx < 10.5) {
    // pixelate
    highp vec2 S = vec2(96.0, 96.0 * (pixelWH.y / pixelWH.x));
    p = (floor(p * S) + 0.5) / S;
  } else if (fxFx > 10.5 && fxFx < 11.5) {
    // mirror kaleidoscope
    p.x = 0.5 + abs(p.x - 0.5);
  }
  return p;
}

// Edge-aware skin smoothing. Radius adapts to the frame size, weights fall
// off on strong edges (eyes, lips, hair) so they stay sharp.
mediump vec3 drgramSkinSmooth(mediump vec2 p, mediump vec3 base, mediump float fw) {
  mediump float strength = beautify * fw;
  if (strength < 0.01) {
    return base;
  }
  mediump vec2 r = (1.4 + 3.4 * beautify) / pixelWH;
  mediump vec3 sum = base;
  mediump float wsum = 1.0;
  for (int i = 0; i < 12; i++) {
    mediump float ang = float(i) * 0.52359877;
    mediump vec2 off = vec2(cos(ang), sin(ang)) * r;
    mediump vec3 S = texture2D(sTexture, p + off).rgb;
    mediump float d = distance(S, base);
    mediump float w = max(0.0, 1.0 - d * 6.0);
    sum += S * w;
    wsum += w;
  }
  mediump vec3 smoothC = sum / wsum;
  mediump float mask = skinMask(base);
  // second, wider pass at low weight for the "porcelain" finish
  mediump vec3 sum2 = smoothC;
  mediump float wsum2 = 1.0;
  for (int i = 0; i < 8; i++) {
    mediump float ang = float(i) * 0.78539816 + 0.39;
    mediump vec2 off = vec2(cos(ang), sin(ang)) * r * 2.1;
    mediump vec3 S = texture2D(sTexture, p + off).rgb;
    mediump float d = distance(S, smoothC);
    mediump float w = max(0.0, 1.0 - d * 5.0);
    sum2 += S * w;
    wsum2 += w;
  }
  smoothC = mix(smoothC, sum2 / wsum2, 0.5);
  return mix(base, smoothC, clamp(strength * mask * 1.25, 0.0, 0.92));
}

// Teeth whitening: inside the mouth ellipse, push bright yellowish pixels
// toward a clean neutral white. Lips/hairline survive the luma gate.
mediump vec3 drgramTeeth(mediump vec3 base, mediump vec2 p) {
  if (fxTeeth < 0.01 || fxFaceCount < 0.5) {
    return base;
  }
  mediump float best = 0.0;
  for (int i = 0; i < 2; i++) {
    if (float(i) < fxFaceCount) {
      mediump vec2 c = fxMouths[i] - vec2(0.0, fxFaces[i].w * 0.10);
      mediump vec2 hs = fxFaces[i].zw * vec2(0.50, 0.26);
      best = max(best, ellipseFalloff(p, c, hs));
    }
  }
  if (best < 0.01) {
    return base;
  }
  mediump float lum = dot(base, vec3(0.299, 0.587, 0.114));
  mediump float yellow = clamp((base.r + base.g) * 0.5 - base.b - 0.06, 0.0, 1.0);
  mediump float gate = smoothstep(0.30, 0.55, lum) * smoothstep(0.03, 0.12, yellow);
  mediump float amt = clamp(best * gate * fxTeeth * 1.1, 0.0, 0.85);
  mediump vec3 white = vec3(clamp(lum + 0.24, 0.62, 1.0));
  return mix(base, white, amt);
}

// Colour-grade effects applied to the whole frame.
mediump vec3 drgramColorFx(mediump vec3 c, mediump vec2 p) {
  mediump float lum = dot(c, vec3(0.299, 0.587, 0.114));
  if (fxFx > 0.5 && fxFx < 1.5) {
    // sunset — warm gradient + soft lift
    mediump float g = 1.0 - p.y;
    c *= vec3(1.10 + 0.06 * g, 0.97, 0.84 + 0.06 * (1.0 - g));
    c += vec3(0.07, 0.02, -0.02) * (0.4 + 0.6 * lum);
    c = clamp(c, 0.0, 1.0);
    c = mix(c, c * c * (3.0 - 2.0 * c), 0.35);
  } else if (fxFx > 1.5 && fxFx < 2.5) {
    // cool — icy blue
    c *= vec3(0.90, 1.0, 1.13);
    mediump float g = dot(c, vec3(0.333));
    c = mix(vec3(g), c, 0.82);
    c += vec3(0.0, 0.01, 0.05) * lum;
  } else if (fxFx > 2.5 && fxFx < 3.5) {
    // pastel — lifted, milky
    c = c * 0.82 + 0.15;
    c.r += 0.03; c.b += 0.035;
    c = clamp(c, 0.0, 1.0);
  } else if (fxFx > 3.5 && fxFx < 4.5) {
    // vintage — sepia + vignette + grain
    mediump vec3 sepia = vec3(
      dot(c, vec3(0.393, 0.769, 0.189)),
      dot(c, vec3(0.349, 0.686, 0.168)),
      dot(c, vec3(0.272, 0.534, 0.131)));
    c = mix(c, sepia, 0.62);
    mediump vec2 vq = (p - 0.5) * vec2(pixelWH.x / pixelWH.y, 1.0);
    c *= 1.0 - 0.42 * dot(vq, vq);
    c += (hash21(p * pixelWH) - 0.5) * 0.045;
  } else if (fxFx > 4.5 && fxFx < 5.5) {
    // blossom — pink glow
    c *= vec3(1.09, 0.95, 1.03);
    c += vec3(0.05, 0.0, 0.03) * (1.0 - lum);
    c = mix(c, c * c * (3.0 - 2.0 * c), 0.25);
  } else if (fxFx > 5.5 && fxFx < 6.5) {
    // noir — hard black & white
    mediump float g = dot(c, vec3(0.30, 0.59, 0.11));
    g = clamp((g - 0.5) * 1.35 + 0.5, 0.0, 1.0);
    mediump vec2 vq = (p - 0.5) * vec2(pixelWH.x / pixelWH.y, 1.0);
    c = vec3(g) * (1.0 - 0.38 * dot(vq, vq));
  } else if (fxFx > 6.5 && fxFx < 7.5) {
    // dream — soft bloom
    mediump vec3 acc = vec3(0.0);
    for (int i = 0; i < 8; i++) {
      mediump float ang = float(i) * 0.78539816;
      mediump vec2 off = vec2(cos(ang), sin(ang)) * 5.0 / pixelWH;
      acc += texture2D(sTexture, p + off).rgb;
    }
    acc *= 0.125;
    mediump float b = dot(acc, vec3(0.333));
    c = mix(c, c + acc * 0.42, smoothstep(0.45, 0.9, b) * 0.8 + 0.12);
    c = mix(c, vec3(dot(c, vec3(0.333))), 0.12);
  } else if (fxFx > 7.5 && fxFx < 8.5) {
    // neon — saturated punch
    mediump float g = dot(c, vec3(0.333));
    c = clamp(mix(vec3(g), c, 1.75), 0.0, 1.0);
    mediump vec3 shifted = c.zxy;
    c = mix(c, mix(c, shifted, 0.22), 0.8);
    c += 0.03;
  } else if (fxFx > 12.5 && fxFx < 13.5) {
    // soft glow
    c = mix(c, sqrt(max(c, vec3(0.0))), 0.18);
    c = mix(vec3(lum), c, 1.08);
    c += vec3(0.018, 0.012, 0.012);
  } else if (fxFx > 13.5 && fxFx < 14.5) {
    // rose
    c *= vec3(1.07, 0.94, 1.02);
    c.r += 0.035 * (1.0 - lum); c.b += 0.012;
    c = mix(vec3(lum), c, 0.94);
  } else if (fxFx > 14.5 && fxFx < 15.5) {
    // honey
    c *= vec3(1.10, 1.025, 0.88);
    c += vec3(0.035, 0.018, -0.008) * (1.0 - lum);
  } else if (fxFx > 15.5 && fxFx < 16.5) {
    // clean
    c = (c - 0.5) * 1.08 + 0.51;
    c = mix(vec3(lum), c, 1.06);
    c += vec3(0.012, 0.015, 0.018);
  } else if (fxFx > 16.5 && fxFx < 17.5) {
    // latte
    c = c * 0.88 + vec3(0.055, 0.045, 0.030);
    c *= vec3(1.035, 1.0, 0.94);
    c = mix(vec3(lum), c, 0.82);
  } else if (fxFx > 17.5 && fxFx < 18.5) {
    // ocean
    c *= vec3(0.91, 1.035, 1.08);
    c.g += 0.025 * (1.0 - lum); c.b += 0.02;
    c = mix(vec3(lum), c, 1.10);
  } else if (fxFx > 18.5 && fxFx < 19.5) {
    // peach
    c = c * 0.92 + vec3(0.045, 0.025, 0.022);
    c *= vec3(1.055, 0.98, 0.96);
    c = mix(vec3(lum), c, 0.90);
  } else if (fxFx > 19.5 && fxFx < 20.5) {
    // glam
    c = (c - 0.5) * 1.12 + 0.52;
    c *= vec3(1.045, 1.01, 1.025);
    c = mix(vec3(lum), c, 1.12);
    c += vec3(0.012, 0.008, 0.012);
  }
  return clamp(c, 0.0, 1.0);
}

// Fun poster/cartoon finish.
mediump vec3 drgramCartoon(mediump vec3 c) {
  if (fxFx > 11.5 && fxFx < 12.5) {
    c = floor(c * 6.0 + 0.5) / 6.0;
  }
  return c;
}

vec4 beautifySample(vec4 base) {
  if (beautify <= 0.001 && fxNose <= 0.001 && fxTeeth <= 0.001 && fxEyes <= 0.001 && fxJaw <= 0.001 && fxFx <= 0.001 && fxBlush <= 0.001 && fxLips <= 0.001 && fxBright <= 0.001)
    return base;
  highp vec2 p = drgramWarp(uv);
  mediump vec4 C;
  if (fxFx > 8.5 && fxFx < 9.5) {
    // glitch — static RGB split (fun)
    mediump vec2 d = vec2(2.6, 1.4) / pixelWH;
    C.r = texture2D(sTexture, p + d).r;
    C.g = texture2D(sTexture, p).g;
    C.b = texture2D(sTexture, p - d).b;
    C.a = base.a;
  } else {
    C = texture2D(sTexture, p);
  }
  mediump float fw = faceWeight(p);
  mediump vec3 rgb = C.rgb;
  rgb = drgramSkinSmooth(p, rgb, fw);
  rgb = drgramTeeth(rgb, p);
  // DrGram Fix 46: glow is now skin-masked so hair/eyes/background stay untouched.
  rgb += beautify * 0.035 * fw * skinMask(rgb);
  // DrGram Fix 46: standalone skin brightening (skin-masked luminance lift).
  if (fxBright > 0.001) {
    rgb += fxBright * 0.055 * fw * skinMask(rgb);
  }
  rgb = clamp(rgb, 0.0, 1.0);
  // DrGram Fix 46: standalone blush + lip tint, anchored on the detected face.
  if ((fxBlush > 0.001 || fxLips > 0.001) && fxFaceCount > 0.5) {
    mediump float blush = 0.0;
    mediump float lips = 0.0;
    for (int i = 0; i < 2; i++) {
      if (float(i) < fxFaceCount) {
        mediump vec2 center = fxFaces[i].xy;
        mediump vec2 halfSize = fxFaces[i].zw;
        mediump vec2 cheekSize = max(halfSize * vec2(0.24, 0.13), vec2(0.001));
        blush = max(blush, ellipseFalloff(p, center + vec2(-halfSize.x * 0.36, halfSize.y * 0.20), cheekSize));
        blush = max(blush, ellipseFalloff(p, center + vec2( halfSize.x * 0.36, halfSize.y * 0.20), cheekSize));
        lips = max(lips, ellipseFalloff(p, fxMouths[i] + vec2(0.0, -halfSize.y * 0.02), max(halfSize * vec2(0.25, 0.085), vec2(0.001))));
      }
    }
    if (fxBlush > 0.001) {
      rgb = mix(rgb, vec3(0.94, 0.48, 0.55), clamp(blush * fxBlush * 0.32, 0.0, 0.32));
    }
    if (fxLips > 0.001) {
      rgb = mix(rgb, vec3(0.80, 0.24, 0.34), clamp(lips * fxLips * 0.42, 0.0, 0.42));
    }
  }
  rgb = drgramColorFx(rgb, p);
  // Glam adds restrained cheek and lip tint only when a face is detected.
  if (fxFx > 19.5 && fxFx < 20.5 && fxFaceCount > 0.5) {
    mediump float blush = 0.0;
    mediump float lips = 0.0;
    for (int i = 0; i < 2; i++) {
      if (float(i) < fxFaceCount) {
        mediump vec2 center = fxFaces[i].xy;
        mediump vec2 halfSize = fxFaces[i].zw;
        mediump vec2 cheekSize = max(halfSize * vec2(0.22, 0.12), vec2(0.001));
        blush = max(blush, ellipseFalloff(p, center + vec2(-halfSize.x * 0.36, halfSize.y * 0.20), cheekSize));
        blush = max(blush, ellipseFalloff(p, center + vec2( halfSize.x * 0.36, halfSize.y * 0.20), cheekSize));
        lips = max(lips, ellipseFalloff(p, fxMouths[i] + vec2(0.0, -halfSize.y * 0.02), max(halfSize * vec2(0.24, 0.075), vec2(0.001))));
      }
    }
    rgb = mix(rgb, vec3(0.92, 0.36, 0.43), clamp(blush * 0.22, 0.0, 0.22));
    rgb = mix(rgb, vec3(0.72, 0.16, 0.28), clamp(lips * 0.18, 0.0, 0.18));
  }
  rgb = drgramCartoon(rgb);
  return vec4(rgb, C.a);
}
void main() {
  if (dual < .5) {
    gl_FragColor = beautifySample(texture2D(sTexture, uv));
  } else {
    gl_FragColor = program();
  }
}
