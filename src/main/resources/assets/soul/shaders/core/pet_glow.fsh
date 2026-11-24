#version 330

// The Pet HUD's level-up glow: CSS `box-shadow: 0 0 0 RING rc, 0 0 BLUR BLUR_SPREAD rc` around a
// rounded card (mocks/pet-hud/mock.css `ph-glow`). Like a box-shadow it paints only OUTSIDE the card.
// The quad is the card expanded by SPREAD on every side; texCoord0/halfSize are composable units from
// the quad center (core/round_rect.vsh), so the card box is halfSize - SPREAD with corner RADIUS.
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

float roundedBoxSDF(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    vec2 b = max(halfSize - SPREAD, vec2(0.0));
    float r = min(RADIUS, min(b.x, b.y));
    float d = roundedBoxSDF(texCoord0, b, r);
    // One physical pixel in composable units (see round_rect.fsh); edges fade over it, centered on the true boundary.
    vec2 jx = dFdx(texCoord0);
    vec2 jy = dFdy(texCoord0);
    float px = max(sqrt(abs(jx.x * jy.y - jx.y * jy.x)), 1e-4);
    // Outside only: exactly what the card's own edge leaves uncovered, so the seam adds up to one.
    float outside = clamp(0.5 + d / px, 0.0, 1.0);
    // Hard ring of RING px.
    float ring = clamp(0.5 - (d - RING) / px, 0.0, 1.0);
    // Blurred copy of the card grown by BLUR_SPREAD: a Gaussian edge (sigma = BLUR / 2), approximated
    // over +-2 sigma.
    float soft = 1.0 - smoothstep(-BLUR, BLUR, d - BLUR_SPREAD);
    float alpha = max(ring, soft) * outside;
    if (alpha <= 0.0) {
        discard;
    }
    vec4 color = vertexColor * ColorModulator;
    fragColor = vec4(color.rgb, color.a * alpha);
}
