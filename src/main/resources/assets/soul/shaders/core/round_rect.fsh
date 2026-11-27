#version 330

// SDF rounded-rectangle fill with anti-aliased edges + optional per-corner masking and soft-shadow
// mode. Ported from the legacy round_rect.fsh (Odin, BSD-3). Works entirely in COMPOSABLE units:
// texCoord0 is the fragment position relative to the quad center and `halfSize` is the quad's
// composable half-extent (both forwarded from the vertex shader). RADIUS / SPREAD are therefore in
// composable units too, so corners scale with the UI (matching the legacy NanoVG renderer).
// RoundRectRenderState snaps the quad to whole physical pixels, so a straight edge lies on a pixel boundary.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

in vec2 texCoord0;
flat in vec2 halfSize;
in vec4 vertexColor;

out vec4 fragColor;
#ifdef CHROMA
// Screen-space chroma (config/gui/render/Chroma.kt): the hue parameter (x + y) * slope - phase, interpolated
// exactly (it is linear in screen position); the color is the full-saturation rainbow at its fractional part.
in float chromaHue;

vec3 chromaRgb(float h) {
    return clamp(abs(fract(h + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
}
#endif

#ifndef RADIUS
#define RADIUS 6.0
#endif
// Which corners to round: bit 1=top-left, 2=top-right, 4=bottom-right, 8=bottom-left.
#ifndef CORNERS
#define CORNERS 15
#endif

float roundedBoxSDF(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

// Radius for the fragment's corner, chosen by the CORNERS mask + the quadrant (sign of p).
float pickRadius(vec2 p) {
    float rTL = ((CORNERS & 1) != 0) ? RADIUS : 0.0;
    float rTR = ((CORNERS & 2) != 0) ? RADIUS : 0.0;
    float rBR = ((CORNERS & 4) != 0) ? RADIUS : 0.0;
    float rBL = ((CORNERS & 8) != 0) ? RADIUS : 0.0;
    float sx = step(0.0, p.x);
    float sy = step(0.0, p.y);
    return mix(mix(rTL, rTR, sx), mix(rBL, rBR, sx), sy);
}

// Coverage of the inside (d < 0) for a pixel whose center lies at distance d: a one-pixel box filter centered on
// the true edge, so the shape keeps its exact size and a ring measures the same along sides and around corners.
float coverage(float d, float px) {
    return clamp(0.5 - d / px, 0.0, 1.0);
}

void main() {
    vec2 p = texCoord0; // composable coords relative to center

    float alpha;
#ifdef SHADOW
    // Soft drop-shadow. The quad is expanded by ~SPREAD around the content; INSET the SDF box by
    // SPREAD so d==SPREAD lands at the quad edge — the fade always fits inside the quad.
    #ifndef SPREAD
    #define SPREAD 16.0
    #endif
    vec2 b = max(halfSize - SPREAD, vec2(0.0));
    float r = min(pickRadius(p), min(b.x, b.y));
    float d = roundedBoxSDF(p, b, r);
    alpha = 1.0 - smoothstep(0.0, SPREAD, d);
#else
  #ifdef PILL
    float r = min(halfSize.x, halfSize.y);
  #else
    float r = min(pickRadius(p), min(halfSize.x, halfSize.y));
  #endif
    float d = roundedBoxSDF(p, halfSize, r);
    // One physical pixel in composable units, from the interpolated position (exact and constant over the quad;
    // the distance field's own derivative is wrong across a square corner's crease and dims that corner pixel).
    vec2 px2 = vec2(dFdx(p.x), dFdy(p.x));
    vec2 py2 = vec2(dFdx(p.y), dFdy(p.y));
    float px = max(sqrt(abs(px2.x * py2.y - px2.y * py2.x)), 1e-4);
  #ifdef STROKE
    // Outline only: STROKE wide inside the quad's edge, following the same rounded corners. The width is rounded up
    // to whole physical pixels so the inner edge is as crisp as the outer one (a 1 px stroke at Soul scale 1.33 is
    // 2 px, not one pixel plus a faint third of one).
    float sw = max(ceil(STROKE / px - 0.05), 1.0) * px;
    alpha = coverage(abs(d + sw * 0.5) - sw * 0.5, px);
    #ifdef DASH
    // Dashes of DASH * 0.55 along the nearer edge's axis.
    vec2 e = abs(p) - halfSize;
    float t = (e.x > e.y) ? p.y : p.x;
    if (fract(t / DASH) > 0.55) alpha = 0.0;
    #endif
    #ifdef DASHP
    // Dashes along the stroke's centerline, parametrized by arc length u from the middle of the nearest corner arc:
    // one dash is centered on every corner arc and on every side center, the period is stretched to fit exactly.
    float rc = max(r - sw * 0.5, 0.01);
    vec2 hc = halfSize - sw * 0.5;
    vec2 cc = hc - rc;
    vec2 ap = abs(p);
    float quarter = rc * 1.5707963;
    float u;
    if (ap.x > cc.x && ap.y > cc.y) {
        u = rc * (atan(ap.x - cc.x, ap.y - cc.y) - 0.7853982);
    } else if (ap.y - cc.y > ap.x - cc.x) {
        u = -(quarter * 0.5 + (cc.x - ap.x));
    } else {
        u = quarter * 0.5 + (cc.y - ap.y);
    }
    float L = quarter * 0.5 + cc.x;
    float T = L / max(floor(L / DASHP + 0.5), 1.0);
    float dd = abs(fract(u / T + 0.5) - 0.5) * T;
    float du = max(fwidth(u), 1e-4);
    alpha *= clamp((T * 0.25 - dd) / du + 0.5, 0.0, 1.0);
    #endif
  #else
    alpha = coverage(d, px);
  #endif
#endif

    if (alpha <= 0.0) {
        discard;
    }
#ifdef CHROMA
    vec4 color = vec4(chromaRgb(chromaHue), vertexColor.a) * ColorModulator;
#else
    vec4 color = vertexColor * ColorModulator;
#endif
    fragColor = vec4(color.rgb, color.a * alpha);
}
