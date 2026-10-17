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
// DrGram Fix 58: FLAT beauty tier — the full DrGram beauty suite (same uniform
// names as the lite program so the Java wiring is unchanged) rewritten with
// NO loops, NO highp, NO macros and NO dynamic array indexing. Every per-face
// block is hand-expanded for face 0 / face 1 with literal constant indices.
// Structurally this stays as close as possible to the upstream CORE shader
// (the only program proven to compile on budget PowerVR GPUs) while still
// carrying smoothing, warps, teeth, under-eye, blush, lips, brightening and
// all 20 colour effects.
uniform float fxNose;
uniform float fxTeeth;
uniform float fxEyes;
uniform float fxJaw;
uniform float fxFx;
uniform float fxFaceCount;
uniform vec4 fxFaces[2];
uniform vec2 fxNoses[2];
uniform vec2 fxMouths[2];
uniform float fxBlush;
uniform float fxLips;
uniform float fxBright;
uniform vec2 fxEyesL[2];
uniform vec2 fxEyesR[2];
uniform float fxEyePrecise;
uniform float fxDark;
uniform float fxSlim;
uniform vec4 fxNoseRect[2];
uniform vec4 fxMouthRect[2];
uniform vec4 fxEyeRectL[2];
uniform vec4 fxEyeRectR[2];
uniform float fxOrganPrecise;

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

// ===================== DrGram Fix 58: flat beauty helpers =====================

mediump float hash21(mediump vec2 p) {
  p = fract(p * vec2(443.897, 441.423));
  p += dot(p, p + 19.19);
  return fract(p.x * p.y);
}

mediump float ellipseFalloff(mediump vec2 p, mediump vec2 c, mediump vec2 halfSize) {
  mediump vec2 q = (p - c) / max(halfSize, vec2(1e-4));
  return 1.0 - smoothstep(0.75, 1.35, length(q));
}

mediump float skinMask(mediump vec3 c) {
  mediump float mx = max(c.r, max(c.g, c.b));
  mediump float mn = min(c.r, min(c.g, c.b));
  mediump float sat = mx - mn;
  mediump float rG = c.r - c.g;
  mediump float gB = c.g - c.b;
  mediump float lum = dot(c, vec3(0.299, 0.587, 0.114));
  mediump float m = 1.0;
  m *= smoothstep(0.02, 0.09, rG);
  m *= 1.0 - smoothstep(0.16, 0.30, rG);
  m *= 1.0 - smoothstep(0.10, 0.22, gB);
  m *= smoothstep(0.12, 0.30, lum);
  m *= 1.0 - smoothstep(0.55, 0.92, sat);
  return clamp(m, 0.0, 1.0);
}

// Face weight, no loop: constant-index expansion for the 2 supported faces.
mediump float faceWeight(mediump vec2 p) {
  if (fxFaceCount < 0.5) {
    return 1.0;
  }
  mediump float w = ellipseFalloff(p, fxFaces[0].xy, fxFaces[0].zw * 1.12);
  if (fxFaceCount > 1.5) {
    w = max(w, ellipseFalloff(p, fxFaces[1].xy, fxFaces[1].zw * 1.12));
  }
  return w;
}

// Nose slimming for face N — literal indices, no loops.
mediump vec2 drgramWarpNose0(mediump vec2 p) {
  if (fxNose <= 0.001) {
    return p;
  }
  mediump vec2 face = fxFaces[0].xy;
  mediump vec2 hs = fxFaces[0].zw;
  mediump vec2 nc = fxNoseRect[0].xy;
  mediump vec2 nh = fxNoseRect[0].zw;
  if (nh.x > 0.002 && nh.y > 0.002) {
    mediump vec2 nq = (p - nc) / max(nh * 1.22, vec2(1e-4));
    mediump float ne = dot(nq, nq);
    if (ne < 1.0) {
      mediump float nf = 1.0 - smoothstep(0.28, 1.0, sqrt(ne));
      mediump float nv = clamp((p.y - (nc.y - nh.y)) / max(2.0 * nh.y, 1e-4), 0.0, 1.0);
      mediump float vw = mix(0.55, 1.0, smoothstep(0.05, 0.60, nv));
      mediump float squeeze = 1.0 - fxNose * 0.32 * nf * nf * vw;
      p.x = nc.x + (p.x - nc.x) / squeeze;
    }
  } else {
    mediump vec2 nq = (p - fxNoses[0]) / max(hs * vec2(0.30, 0.32), vec2(1e-4));
    mediump float ne = dot(nq, nq);
    if (ne < 1.0) {
      mediump float nf = 1.0 - ne;
      mediump float squeeze = 1.0 - fxNose * 0.30 * nf * nf;
      p.x = fxNoses[0].x + (p.x - fxNoses[0].x) / squeeze;
    }
  }
  return p;
}
mediump vec2 drgramWarpNose1(mediump vec2 p) {
  if (fxNose <= 0.001) {
    return p;
  }
  mediump vec2 face = fxFaces[1].xy;
  mediump vec2 hs = fxFaces[1].zw;
  mediump vec2 nc = fxNoseRect[1].xy;
  mediump vec2 nh = fxNoseRect[1].zw;
  if (nh.x > 0.002 && nh.y > 0.002) {
    mediump vec2 nq = (p - nc) / max(nh * 1.22, vec2(1e-4));
    mediump float ne = dot(nq, nq);
    if (ne < 1.0) {
      mediump float nf = 1.0 - smoothstep(0.28, 1.0, sqrt(ne));
      mediump float nv = clamp((p.y - (nc.y - nh.y)) / max(2.0 * nh.y, 1e-4), 0.0, 1.0);
      mediump float vw = mix(0.55, 1.0, smoothstep(0.05, 0.60, nv));
      mediump float squeeze = 1.0 - fxNose * 0.32 * nf * nf * vw;
      p.x = nc.x + (p.x - nc.x) / squeeze;
    }
  } else {
    mediump vec2 nq = (p - fxNoses[1]) / max(hs * vec2(0.30, 0.32), vec2(1e-4));
    mediump float ne = dot(nq, nq);
    if (ne < 1.0) {
      mediump float nf = 1.0 - ne;
      mediump float squeeze = 1.0 - fxNose * 0.30 * nf * nf;
      p.x = fxNoses[1].x + (p.x - fxNoses[1].x) / squeeze;
    }
  }
  return p;
}

// Eye enlargement for face N — both eyes inline, literal indices.
mediump vec2 drgramWarpEyes0(mediump vec2 p) {
  if (fxEyes <= 0.001) {
    return p;
  }
  mediump vec2 face = fxFaces[0].xy;
  mediump vec2 hs = fxFaces[0].zw;
  mediump vec2 ecL = face + vec2(-hs.x * 0.27, -hs.y * 0.20);
  mediump vec2 ecR = face + vec2( hs.x * 0.27, -hs.y * 0.20);
  mediump vec2 ehL = hs * vec2(0.19, 0.13);
  mediump vec2 ehR = hs * vec2(0.19, 0.13);
  if (fxEyePrecise > 0.5) {
    ecL = fxEyesL[0];
    ecR = fxEyesR[0];
    if (fxEyeRectL[0].z > 0.002) {
      ecL = fxEyeRectL[0].xy;
      ehL = fxEyeRectL[0].zw * 1.30;
    } else {
      ehL = hs * vec2(0.15, 0.105);
    }
    if (fxEyeRectR[0].z > 0.002) {
      ecR = fxEyeRectR[0].xy;
      ehR = fxEyeRectR[0].zw * 1.30;
    } else {
      ehR = hs * vec2(0.15, 0.105);
    }
  }
  mediump vec2 eqL = (p - ecL) / max(ehL, vec2(1e-4));
  mediump float edL = dot(eqL, eqL);
  if (edL < 1.0) {
    mediump float em = 1.0 - smoothstep(0.42, 1.0, edL);
    p = ecL + (p - ecL) * (1.0 - fxEyes * 0.16 * em);
  }
  mediump vec2 eqR = (p - ecR) / max(ehR, vec2(1e-4));
  mediump float edR = dot(eqR, eqR);
  if (edR < 1.0) {
    mediump float em = 1.0 - smoothstep(0.42, 1.0, edR);
    p = ecR + (p - ecR) * (1.0 - fxEyes * 0.16 * em);
  }
  return p;
}
mediump vec2 drgramWarpEyes1(mediump vec2 p) {
  if (fxEyes <= 0.001) {
    return p;
  }
  mediump vec2 face = fxFaces[1].xy;
  mediump vec2 hs = fxFaces[1].zw;
  mediump vec2 ecL = face + vec2(-hs.x * 0.27, -hs.y * 0.20);
  mediump vec2 ecR = face + vec2( hs.x * 0.27, -hs.y * 0.20);
  mediump vec2 ehL = hs * vec2(0.19, 0.13);
  mediump vec2 ehR = hs * vec2(0.19, 0.13);
  if (fxEyePrecise > 0.5) {
    ecL = fxEyesL[1];
    ecR = fxEyesR[1];
    if (fxEyeRectL[1].z > 0.002) {
      ecL = fxEyeRectL[1].xy;
      ehL = fxEyeRectL[1].zw * 1.30;
    } else {
      ehL = hs * vec2(0.15, 0.105);
    }
    if (fxEyeRectR[1].z > 0.002) {
      ecR = fxEyeRectR[1].xy;
      ehR = fxEyeRectR[1].zw * 1.30;
    } else {
      ehR = hs * vec2(0.15, 0.105);
    }
  }
  mediump vec2 eqL = (p - ecL) / max(ehL, vec2(1e-4));
  mediump float edL = dot(eqL, eqL);
  if (edL < 1.0) {
    mediump float em = 1.0 - smoothstep(0.42, 1.0, edL);
    p = ecL + (p - ecL) * (1.0 - fxEyes * 0.16 * em);
  }
  mediump vec2 eqR = (p - ecR) / max(ehR, vec2(1e-4));
  mediump float edR = dot(eqR, eqR);
  if (edR < 1.0) {
    mediump float em = 1.0 - smoothstep(0.42, 1.0, edR);
    p = ecR + (p - ecR) * (1.0 - fxEyes * 0.16 * em);
  }
  return p;
}

// Jaw + cheek slimming for face N — literal indices.
mediump vec2 drgramWarpJawSlim0(mediump vec2 p) {
  mediump vec2 face = fxFaces[0].xy;
  mediump vec2 hs = fxFaces[0].zw;
  if (fxJaw > 0.001) {
    mediump float jy = (p.y - (face.y + hs.y * 0.05)) / max(hs.y * 0.95, 1e-4);
    mediump float jv = smoothstep(0.05, 0.34, jy) * (1.0 - smoothstep(0.88, 1.10, jy));
    mediump float jl = 1.0 - smoothstep(hs.x * 0.50, hs.x * 0.98, abs(p.x - face.x));
    mediump float ja = fxJaw * 0.12 * jv * jl;
    p.x = face.x + (p.x - face.x) / max(0.82, 1.0 - ja);
  }
  if (fxSlim > 0.001) {
    mediump float sb = smoothstep(-0.18, 0.04, (p.y - face.y) / max(hs.y, 1e-4))
        * (1.0 - smoothstep(0.34, 0.58, (p.y - face.y) / max(hs.y, 1e-4)));
    mediump float sl = 1.0 - smoothstep(hs.x * 0.44, hs.x * 0.96, abs(p.x - face.x));
    mediump float sa = fxSlim * 0.10 * sb * sl;
    p.x = face.x + (p.x - face.x) / max(0.84, 1.0 - sa);
  }
  return p;
}
mediump vec2 drgramWarpJawSlim1(mediump vec2 p) {
  mediump vec2 face = fxFaces[1].xy;
  mediump vec2 hs = fxFaces[1].zw;
  if (fxJaw > 0.001) {
    mediump float jy = (p.y - (face.y + hs.y * 0.05)) / max(hs.y * 0.95, 1e-4);
    mediump float jv = smoothstep(0.05, 0.34, jy) * (1.0 - smoothstep(0.88, 1.10, jy));
    mediump float jl = 1.0 - smoothstep(hs.x * 0.50, hs.x * 0.98, abs(p.x - face.x));
    mediump float ja = fxJaw * 0.12 * jv * jl;
    p.x = face.x + (p.x - face.x) / max(0.82, 1.0 - ja);
  }
  if (fxSlim > 0.001) {
    mediump float sb = smoothstep(-0.18, 0.04, (p.y - face.y) / max(hs.y, 1e-4))
        * (1.0 - smoothstep(0.34, 0.58, (p.y - face.y) / max(hs.y, 1e-4)));
    mediump float sl = 1.0 - smoothstep(hs.x * 0.44, hs.x * 0.96, abs(p.x - face.x));
    mediump float sa = fxSlim * 0.10 * sb * sl;
    p.x = face.x + (p.x - face.x) / max(0.84, 1.0 - sa);
  }
  return p;
}

mediump vec2 drgramWarp(mediump vec2 p) {
  if (fxFaceCount > 0.5) {
    p = drgramWarpNose0(p);
    p = drgramWarpEyes0(p);
    p = drgramWarpJawSlim0(p);
  }
  if (fxFaceCount > 1.5) {
    p = drgramWarpNose1(p);
    p = drgramWarpEyes1(p);
    p = drgramWarpJawSlim1(p);
  }
  // fun sampling effects
  if (fxFx > 9.5 && fxFx < 10.5) {
    mediump vec2 S = vec2(96.0, 96.0 * (pixelWH.y / pixelWH.x));
    p = (floor(p * S) + 0.5) / S;
  } else if (fxFx > 10.5 && fxFx < 11.5) {
    p.x = 0.5 + abs(p.x - 0.5);
  }
  return p;
}

// Edge-aware skin smoothing, fully unrolled: 8 near taps + 4 wide taps.
mediump vec3 drgramSkinSmooth(mediump vec2 p, mediump vec3 base, mediump float fw) {
  mediump float strength = beautify * fw;
  if (strength < 0.01) {
    return base;
  }
  mediump vec2 r = (1.6 + 3.2 * beautify) / pixelWH;
  mediump vec3 sum = base;
  mediump float wsum = 1.0;
  mediump vec3 S;
  mediump float w;
  S = texture2D(sTexture, p + vec2( r.x, 0.0)).rgb;
  w = max(0.0, 1.0 - distance(S, base) * 6.0); sum += S * w; wsum += w;
  S = texture2D(sTexture, p + vec2(-r.x, 0.0)).rgb;
  w = max(0.0, 1.0 - distance(S, base) * 6.0); sum += S * w; wsum += w;
  S = texture2D(sTexture, p + vec2(0.0,  r.y)).rgb;
  w = max(0.0, 1.0 - distance(S, base) * 6.0); sum += S * w; wsum += w;
  S = texture2D(sTexture, p + vec2(0.0, -r.y)).rgb;
  w = max(0.0, 1.0 - distance(S, base) * 6.0); sum += S * w; wsum += w;
  S = texture2D(sTexture, p + r * 0.71).rgb;
  w = max(0.0, 1.0 - distance(S, base) * 6.0); sum += S * w; wsum += w;
  S = texture2D(sTexture, p - r * 0.71).rgb;
  w = max(0.0, 1.0 - distance(S, base) * 6.0); sum += S * w; wsum += w;
  S = texture2D(sTexture, p + vec2(-r.x,  r.y) * 0.71).rgb;
  w = max(0.0, 1.0 - distance(S, base) * 6.0); sum += S * w; wsum += w;
  S = texture2D(sTexture, p + vec2( r.x, -r.y) * 0.71).rgb;
  w = max(0.0, 1.0 - distance(S, base) * 6.0); sum += S * w; wsum += w;
  mediump vec3 smoothC = sum / wsum;
  mediump vec3 sum2 = smoothC;
  mediump float wsum2 = 1.0;
  S = texture2D(sTexture, p + vec2( r.x * 2.1, 0.0)).rgb;
  w = max(0.0, 1.0 - distance(S, smoothC) * 5.0); sum2 += S * w; wsum2 += w;
  S = texture2D(sTexture, p + vec2(-r.x * 2.1, 0.0)).rgb;
  w = max(0.0, 1.0 - distance(S, smoothC) * 5.0); sum2 += S * w; wsum2 += w;
  S = texture2D(sTexture, p + vec2(0.0,  r.y * 2.1)).rgb;
  w = max(0.0, 1.0 - distance(S, smoothC) * 5.0); sum2 += S * w; wsum2 += w;
  S = texture2D(sTexture, p + vec2(0.0, -r.y * 2.1)).rgb;
  w = max(0.0, 1.0 - distance(S, smoothC) * 5.0); sum2 += S * w; wsum2 += w;
  smoothC = mix(smoothC, sum2 / wsum2, 0.5);
  return mix(base, smoothC, clamp(strength * skinMask(base) * 1.25, 0.0, 0.92));
}

// Teeth whitening, constant-index expansion over the 2 faces.
mediump vec3 drgramTeeth(mediump vec3 base, mediump vec2 p) {
  if (fxTeeth < 0.01 || fxFaceCount < 0.5) {
    return base;
  }
  mediump float best = 0.0;
  {
    mediump vec4 mr = fxMouthRect[0];
    mediump vec2 tc;
    mediump vec2 th;
    if (mr.z > 0.002) {
      tc = mr.xy - vec2(0.0, mr.w * 0.10);
      th = mr.zw * vec2(0.80, 0.62);
    } else {
      tc = fxMouths[0] - vec2(0.0, fxFaces[0].w * 0.10);
      th = fxFaces[0].zw * vec2(0.50, 0.26);
    }
    best = max(best, ellipseFalloff(p, tc, th));
  }
  if (fxFaceCount > 1.5) {
    mediump vec4 mr = fxMouthRect[1];
    mediump vec2 tc;
    mediump vec2 th;
    if (mr.z > 0.002) {
      tc = mr.xy - vec2(0.0, mr.w * 0.10);
      th = mr.zw * vec2(0.80, 0.62);
    } else {
      tc = fxMouths[1] - vec2(0.0, fxFaces[1].w * 0.10);
      th = fxFaces[1].zw * vec2(0.50, 0.26);
    }
    best = max(best, ellipseFalloff(p, tc, th));
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

// Colour-grade effects (whole frame). All straight-line ALU except the
// dream bloom, which uses 4 explicit taps instead of a loop.
mediump vec3 drgramColorFx(mediump vec3 c, mediump vec2 p) {
  mediump float lum = dot(c, vec3(0.299, 0.587, 0.114));
  if (fxFx > 0.5 && fxFx < 1.5) {
    mediump float g = 1.0 - p.y;
    c *= vec3(1.10 + 0.06 * g, 0.97, 0.84 + 0.06 * (1.0 - g));
    c += vec3(0.07, 0.02, -0.02) * (0.4 + 0.6 * lum);
    c = clamp(c, 0.0, 1.0);
    c = mix(c, c * c * (3.0 - 2.0 * c), 0.35);
  } else if (fxFx > 1.5 && fxFx < 2.5) {
    c *= vec3(0.90, 1.0, 1.13);
    mediump float g = dot(c, vec3(0.333));
    c = mix(vec3(g), c, 0.82);
    c += vec3(0.0, 0.01, 0.05) * lum;
  } else if (fxFx > 2.5 && fxFx < 3.5) {
    c = c * 0.82 + 0.15;
    c.r += 0.03; c.b += 0.035;
    c = clamp(c, 0.0, 1.0);
  } else if (fxFx > 3.5 && fxFx < 4.5) {
    mediump vec3 sepia = vec3(
      dot(c, vec3(0.393, 0.769, 0.189)),
      dot(c, vec3(0.349, 0.686, 0.168)),
      dot(c, vec3(0.272, 0.534, 0.131)));
    c = mix(c, sepia, 0.62);
    mediump vec2 vq = (p - 0.5) * vec2(pixelWH.x / pixelWH.y, 1.0);
    c *= 1.0 - 0.42 * dot(vq, vq);
    c += (hash21(p * pixelWH) - 0.5) * 0.045;
  } else if (fxFx > 4.5 && fxFx < 5.5) {
    c *= vec3(1.09, 0.95, 1.03);
    c += vec3(0.05, 0.0, 0.03) * (1.0 - lum);
    c = mix(c, c * c * (3.0 - 2.0 * c), 0.25);
  } else if (fxFx > 5.5 && fxFx < 6.5) {
    mediump float g = dot(c, vec3(0.30, 0.59, 0.11));
    g = clamp((g - 0.5) * 1.35 + 0.5, 0.0, 1.0);
    mediump vec2 vq = (p - 0.5) * vec2(pixelWH.x / pixelWH.y, 1.0);
    c = vec3(g) * (1.0 - 0.38 * dot(vq, vq));
  } else if (fxFx > 6.5 && fxFx < 7.5) {
    mediump vec3 acc = texture2D(sTexture, p + vec2( 5.0,  5.0) / pixelWH).rgb;
    acc += texture2D(sTexture, p + vec2(-5.0,  5.0) / pixelWH).rgb;
    acc += texture2D(sTexture, p + vec2( 5.0, -5.0) / pixelWH).rgb;
    acc += texture2D(sTexture, p + vec2(-5.0, -5.0) / pixelWH).rgb;
    acc *= 0.25;
    mediump float b = dot(acc, vec3(0.333));
    c = mix(c, c + acc * 0.42, smoothstep(0.45, 0.9, b) * 0.8 + 0.12);
    c = mix(c, vec3(dot(c, vec3(0.333))), 0.12);
  } else if (fxFx > 7.5 && fxFx < 8.5) {
    mediump float g = dot(c, vec3(0.333));
    c = clamp(mix(vec3(g), c, 1.75), 0.0, 1.0);
    mediump vec3 shifted = c.zxy;
    c = mix(c, mix(c, shifted, 0.22), 0.8);
    c += 0.03;
  } else if (fxFx > 12.5 && fxFx < 13.5) {
    c = mix(c, sqrt(max(c, vec3(0.0))), 0.18);
    c = mix(vec3(lum), c, 1.08);
    c += vec3(0.018, 0.012, 0.012);
  } else if (fxFx > 13.5 && fxFx < 14.5) {
    c *= vec3(1.07, 0.94, 1.02);
    c.r += 0.035 * (1.0 - lum); c.b += 0.012;
    c = mix(vec3(lum), c, 0.94);
  } else if (fxFx > 14.5 && fxFx < 15.5) {
    c *= vec3(1.10, 1.025, 0.88);
    c += vec3(0.035, 0.018, -0.008) * (1.0 - lum);
  } else if (fxFx > 15.5 && fxFx < 16.5) {
    c = (c - 0.5) * 1.08 + 0.51;
    c = mix(vec3(lum), c, 1.06);
    c += vec3(0.012, 0.015, 0.018);
  } else if (fxFx > 16.5 && fxFx < 17.5) {
    c = c * 0.88 + vec3(0.055, 0.045, 0.030);
    c *= vec3(1.035, 1.0, 0.94);
    c = mix(vec3(lum), c, 0.82);
  } else if (fxFx > 17.5 && fxFx < 18.5) {
    c *= vec3(0.91, 1.035, 1.08);
    c.g += 0.025 * (1.0 - lum); c.b += 0.02;
    c = mix(vec3(lum), c, 1.10);
  } else if (fxFx > 18.5 && fxFx < 19.5) {
    c = c * 0.92 + vec3(0.045, 0.025, 0.022);
    c *= vec3(1.055, 0.98, 0.96);
    c = mix(vec3(lum), c, 0.90);
  } else if (fxFx > 19.5 && fxFx < 20.5) {
    c = (c - 0.5) * 1.12 + 0.52;
    c *= vec3(1.045, 1.01, 1.025);
    c = mix(vec3(lum), c, 1.12);
    c += vec3(0.012, 0.008, 0.012);
  }
  return clamp(c, 0.0, 1.0);
}

mediump vec3 drgramCartoon(mediump vec3 c) {
  if (fxFx > 11.5 && fxFx < 12.5) {
    c = floor(c * 6.0 + 0.5) / 6.0;
  }
  return c;
}

// Under-eye brightening zones — 4 literal blocks (2 faces x 2 eyes).
mediump float drgramUnder00(mediump vec2 p) {
  mediump vec2 fhs = fxFaces[0].zw;
  mediump vec2 ec = fxEyesL[0];
  if (fxEyePrecise < 0.5) {
    ec = fxFaces[0].xy + vec2(-fhs.x * 0.27, -fhs.y * 0.20);
  }
  mediump vec2 c = ec + vec2(0.0, fhs.y * 0.085);
  mediump vec2 ehs = vec2(fhs.x * 0.17, fhs.y * 0.075);
  mediump vec4 er = fxEyeRectL[0];
  if (er.z > 0.002) {
    c = ec + vec2(0.0, er.w * 1.15);
    ehs = vec2(er.z * 1.25, er.w * 0.80);
  }
  return ellipseFalloff(p, c, ehs);
}
mediump float drgramUnder01(mediump vec2 p) {
  mediump vec2 fhs = fxFaces[0].zw;
  mediump vec2 ec = fxEyesR[0];
  if (fxEyePrecise < 0.5) {
    ec = fxFaces[0].xy + vec2(fhs.x * 0.27, -fhs.y * 0.20);
  }
  mediump vec2 c = ec + vec2(0.0, fhs.y * 0.085);
  mediump vec2 ehs = vec2(fhs.x * 0.17, fhs.y * 0.075);
  mediump vec4 er = fxEyeRectR[0];
  if (er.z > 0.002) {
    c = ec + vec2(0.0, er.w * 1.15);
    ehs = vec2(er.z * 1.25, er.w * 0.80);
  }
  return ellipseFalloff(p, c, ehs);
}
mediump float drgramUnder10(mediump vec2 p) {
  mediump vec2 fhs = fxFaces[1].zw;
  mediump vec2 ec = fxEyesL[1];
  if (fxEyePrecise < 0.5) {
    ec = fxFaces[1].xy + vec2(-fhs.x * 0.27, -fhs.y * 0.20);
  }
  mediump vec2 c = ec + vec2(0.0, fhs.y * 0.085);
  mediump vec2 ehs = vec2(fhs.x * 0.17, fhs.y * 0.075);
  mediump vec4 er = fxEyeRectL[1];
  if (er.z > 0.002) {
    c = ec + vec2(0.0, er.w * 1.15);
    ehs = vec2(er.z * 1.25, er.w * 0.80);
  }
  return ellipseFalloff(p, c, ehs);
}
mediump float drgramUnder11(mediump vec2 p) {
  mediump vec2 fhs = fxFaces[1].zw;
  mediump vec2 ec = fxEyesR[1];
  if (fxEyePrecise < 0.5) {
    ec = fxFaces[1].xy + vec2(fhs.x * 0.27, -fhs.y * 0.20);
  }
  mediump vec2 c = ec + vec2(0.0, fhs.y * 0.085);
  mediump vec2 ehs = vec2(fhs.x * 0.17, fhs.y * 0.075);
  mediump vec4 er = fxEyeRectR[1];
  if (er.z > 0.002) {
    c = ec + vec2(0.0, er.w * 1.15);
    ehs = vec2(er.z * 1.25, er.w * 0.80);
  }
  return ellipseFalloff(p, c, ehs);
}

// Blush + lip tint zones per face — literal indices, inout accumulators.
void drgramBlushLips0(mediump vec2 p, inout mediump float blush, inout mediump float lips) {
  mediump vec2 center = fxFaces[0].xy;
  mediump vec2 halfSize = fxFaces[0].zw;
  mediump vec2 cheekSize = max(halfSize * vec2(0.24, 0.13), vec2(0.001));
  blush = max(blush, ellipseFalloff(p, center + vec2(-halfSize.x * 0.36, halfSize.y * 0.20), cheekSize));
  blush = max(blush, ellipseFalloff(p, center + vec2( halfSize.x * 0.36, halfSize.y * 0.20), cheekSize));
  mediump vec4 mr = fxMouthRect[0];
  if (mr.z > 0.002) {
    lips = max(lips, ellipseFalloff(p, mr.xy, max(mr.zw * vec2(1.06, 1.14), vec2(0.001))));
  } else {
    lips = max(lips, ellipseFalloff(p, fxMouths[0] + vec2(0.0, -halfSize.y * 0.02), max(halfSize * vec2(0.25, 0.085), vec2(0.001))));
  }
}
void drgramBlushLips1(mediump vec2 p, inout mediump float blush, inout mediump float lips) {
  mediump vec2 center = fxFaces[1].xy;
  mediump vec2 halfSize = fxFaces[1].zw;
  mediump vec2 cheekSize = max(halfSize * vec2(0.24, 0.13), vec2(0.001));
  blush = max(blush, ellipseFalloff(p, center + vec2(-halfSize.x * 0.36, halfSize.y * 0.20), cheekSize));
  blush = max(blush, ellipseFalloff(p, center + vec2( halfSize.x * 0.36, halfSize.y * 0.20), cheekSize));
  mediump vec4 mr = fxMouthRect[1];
  if (mr.z > 0.002) {
    lips = max(lips, ellipseFalloff(p, mr.xy, max(mr.zw * vec2(1.06, 1.14), vec2(0.001))));
  } else {
    lips = max(lips, ellipseFalloff(p, fxMouths[1] + vec2(0.0, -halfSize.y * 0.02), max(halfSize * vec2(0.25, 0.085), vec2(0.001))));
  }
}

vec4 beautifySample(vec4 base) {
  if (beautify <= 0.001 && fxNose <= 0.001 && fxTeeth <= 0.001 && fxEyes <= 0.001 && fxJaw <= 0.001 && fxFx <= 0.001 && fxBlush <= 0.001 && fxLips <= 0.001 && fxBright <= 0.001 && fxDark <= 0.001 && fxSlim <= 0.001)
    return base;
  mediump vec2 p = drgramWarp(uv);
  mediump vec4 C;
  if (fxFx > 8.5 && fxFx < 9.5) {
    // glitch — static RGB split
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
  rgb += beautify * 0.035 * fw * skinMask(rgb);
  if (fxBright > 0.001) {
    rgb += fxBright * 0.055 * fw * skinMask(rgb);
  }
  rgb = clamp(rgb, 0.0, 1.0);
  if (fxDark > 0.001 && fxFaceCount > 0.5) {
    mediump float undereye = drgramUnder00(p);
    undereye = max(undereye, drgramUnder01(p));
    if (fxFaceCount > 1.5) {
      undereye = max(undereye, drgramUnder10(p));
      undereye = max(undereye, drgramUnder11(p));
    }
    if (undereye > 0.001) {
      mediump float lum = dot(rgb, vec3(0.299, 0.587, 0.114));
      mediump float dark = 1.0 - smoothstep(0.18, 0.52, lum);
      mediump float amt = clamp(undereye * dark * fxDark, 0.0, 0.55);
      rgb = mix(rgb, rgb + vec3(0.075, 0.066, 0.060), amt);
    }
  }
  if ((fxBlush > 0.001 || fxLips > 0.001) && fxFaceCount > 0.5) {
    mediump float blush = 0.0;
    mediump float lips = 0.0;
    drgramBlushLips0(p, blush, lips);
    if (fxFaceCount > 1.5) {
      drgramBlushLips1(p, blush, lips);
    }
    if (fxBlush > 0.001) {
      rgb = mix(rgb, vec3(0.94, 0.48, 0.55), clamp(blush * fxBlush * 0.32, 0.0, 0.32));
    }
    if (fxLips > 0.001) {
      rgb = mix(rgb, vec3(0.80, 0.24, 0.34), clamp(lips * fxLips * 0.42, 0.0, 0.42));
    }
  }
  rgb = drgramColorFx(rgb, p);
  // Glam preset: same flat masks, slightly stronger cheek/lip tint.
  if (fxFx > 19.5 && fxFx < 20.5 && fxFaceCount > 0.5) {
    mediump float blush = 0.0;
    mediump float lips = 0.0;
    drgramBlushLips0(p, blush, lips);
    if (fxFaceCount > 1.5) {
      drgramBlushLips1(p, blush, lips);
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
