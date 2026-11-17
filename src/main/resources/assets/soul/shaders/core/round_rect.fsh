#version 330

// SDF rounded-rectangle fill with anti-aliased edges + optional per-corner masking and soft-shadow
// mode. Ported from the legacy round_rect.fsh (Odin, BSD-3). Works entirely in COMPOSABLE units:
// texCoord0 is the fragment position relative to the quad center and `halfSize` is the quad's
// composable half-extent (both forwarded from the vertex shader). RADIUS / SPREAD are therefore in
// composable units too, so corners scale with the UI (matching the legacy NanoVG renderer). AA width
// is derived from the screen-space derivative so edges stay crisp at any scale.
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
    // ~1px AA in composable units, recovered from the screen-space derivative of the distance field.
    float aa = max(fwidth(d), 1e-4);
    alpha = 1.0 - smoothstep(0.0, aa, d);
  #ifdef STROKE
    // Outline only: STROKE wide, inside the quad's edge, following the same rounded corners.
    alpha = 1.0 - smoothstep(0.0, aa, abs(d + STROKE * 0.5) - STROKE * 0.5);
    #ifdef DASH
    // Dashes of DASH * 0.55 along the nearer edge's axis.
    vec2 e = abs(p) - halfSize;
    float t = (e.x > e.y) ? p.y : p.x;
    if (fract(t / DASH) > 0.55) alpha = 0.0;
    #endif
  #endif
#endif

    if (alpha <= 0.0) {
        discard;
    }
    vec4 color = vertexColor * ColorModulator;
    fragColor = vec4(color.rgb, color.a * alpha);
}
