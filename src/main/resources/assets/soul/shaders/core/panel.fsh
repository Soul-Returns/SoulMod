#version 330

// Soul's rounded panel (config/gui/render/SoulPanel.kt): a fill and a ring inside the quad's edge, concentric by
// construction. Both come from one distance field d of the outer shape: the ring covers -width < d < 0, the fill
// d < -width (the inner offset of the same rounded box: same arc centers, radius minus the width, square once the
// width passes the radius). Their coverages add up to the shape's own, so no seam, no fill outside the ring and no
// ring color under the fill. The ring can blend two colors around its centerline (gradient), moving with the phase.
// Without a ring the fill is cut to a clip box (SoulPanel.slice: a stripe as a slice of its card's shape).
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

in vec2 texCoord0;
in float chromaHue;
flat in vec2 halfSize;
flat in vec4 fillColor;
flat in vec4 ringColor;
flat in vec4 ringColor2;
flat in vec3 chromaOn;
flat in vec3 shape;
flat in int corners;
flat in vec4 clipBox;

out vec4 fragColor;

// Chroma palette of mocks/scoreboard: FF5555 FFFF55 55FF55 55FFFF 5555FF FF55FF, linear between stops, wrapping.
// Identical in every Soul shader and equal to Kotlin's Chroma.rainbow (ChromaTest checks both).
vec3 chromaRgb(float h) {
    return 1.0 / 3.0 + 2.0 / 3.0 * clamp(abs(fract(h + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
}

float roundedBoxSDF(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

// One physical pixel of coverage centered on the edge (as core/round_rect).
float coverage(float d, float px) {
    return clamp(0.5 - d / px, 0.0, 1.0);
}

// Position along a rounded box's outline (half-size hc, corner radius rc) as a fraction of its length, clockwise from
// the top center (GUI y points down): arc length, so the gradient moves at one speed along sides and corners.
float perimeter(vec2 p, vec2 hc, float rc) {
    vec2 c = max(hc - rc, vec2(0.0));
    float quarter = c.x + rc * 1.5707963 + c.y;
    vec2 a = abs(p);
    float v;
    if (a.x > c.x && a.y > c.y) {
        v = c.x + rc * atan(a.x - c.x, a.y - c.y);
    } else if (hc.y - a.y < hc.x - a.x) {
        v = min(a.x, c.x);
    } else {
        v = quarter - min(a.y, c.y);
    }
    float u = (p.y < 0.0) ? ((p.x >= 0.0) ? v : 4.0 * quarter - v) : ((p.x >= 0.0) ? 2.0 * quarter - v : 2.0 * quarter + v);
    return u / max(4.0 * quarter, 1e-4);
}

vec4 paint(vec4 c, float chroma) {
    return (chroma > 0.5) ? vec4(chromaRgb(chromaHue), c.a) : c;
}

void main() {
    vec2 p = texCoord0;
    float rMax = min(shape.x < 0.0 ? 1e6 : shape.x, min(halfSize.x, halfSize.y));
    // The fragment's corner (1 TL, 2 TR, 4 BR, 8 BL; GUI y points down) is round only when its bit is set.
    int corner = (p.x < 0.0) ? ((p.y < 0.0) ? 1 : 8) : ((p.y < 0.0) ? 2 : 4);
    float r = ((corners & corner) != 0) ? rMax : 0.0;
    float d = roundedBoxSDF(p, halfSize, r);
    // One physical pixel in composable units, from the interpolated position (as core/round_rect).
    vec2 px2 = vec2(dFdx(p.x), dFdy(p.x));
    vec2 py2 = vec2(dFdx(p.y), dFdy(p.y));
    float px = max(sqrt(abs(px2.x * py2.y - px2.y * py2.x)), 1e-4);
    float outer = coverage(d, px);

    vec4 fill = paint(fillColor, chromaOn.x);
    vec4 ring = vec4(0.0);
    float fillA = outer;
    float ringA = 0.0;
    if (shape.y > 0.0) {
        // Whole physical pixels, like round_rect's STROKE: the inner edge stays as crisp as the outer one.
        float sw = max(ceil(shape.y / px - 0.05), 1.0) * px;
        fillA = coverage(d + sw, px);
        ringA = outer - fillA;
        ring = paint(ringColor, chromaOn.y);
        if (shape.z >= 0.0) {
            float t = perimeter(p, halfSize - sw * 0.5, max(rMax - sw * 0.5, 0.0)) - shape.z;
            ring = mix(ring, paint(ringColor2, chromaOn.z), 0.5 - 0.5 * cos(6.2831853 * t));
        }
    } else {
        // The clip box's sides lie on pixel boundaries (snapped), so a hard test at pixel centers cuts them exactly.
        vec2 o = max(clipBox.xy - p, p - clipBox.zw);
        fillA *= step(max(o.x, o.y), 0.0);
    }
    float a = fill.a * fillA + ring.a * ringA;
    if (a <= 0.0) {
        discard;
    }
    vec3 rgb = (fill.rgb * (fill.a * fillA) + ring.rgb * (ring.a * ringA)) / a;
    fragColor = vec4(rgb, a) * ColorModulator;
}
