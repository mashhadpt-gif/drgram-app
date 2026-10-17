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
  // DrGram Fix 60: face beautification (smoothing, warps, teeth,
  // under-eye, blush/lips, brightening) removed on purpose — only the
  // colour FX (drgramColorFx / glitch / glam / cartoon) remain. All
  // beauty uniforms stay declared so the Java wiring keeps its
  // guarded no-op paths.
  if (fxFx <= 0.001)
    return base;
  mediump vec2 p = uv;
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
  mediump vec3 rgb = C.rgb;
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
