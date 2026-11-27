#version 330

// MSDF text: reconstruct a crisp glyph edge from a multi-channel signed-distance-field atlas at ANY
// scale (Chlumsky median-of-3 + screen-space range from the UV derivative). DISTANCE_RANGE must
// match the atlas's distanceRange (see msdf-atlas-gen -pxrange).
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;
#ifdef CHROMA
// Screen-space chroma (config/gui/render/Chroma.kt): the hue parameter (x + y) * slope - phase, interpolated
// exactly (it is linear in screen position); the color is the chroma palette at its fractional part.
in float chromaHue;

// Chroma palette of mocks/scoreboard: FF5555 FFFF55 55FF55 55FFFF 5555FF FF55FF, linear between stops, wrapping.
// Identical in every Soul shader and equal to Kotlin's Chroma.rainbow (ChromaTest checks both).
vec3 chromaRgb(float h) {
    return 1.0 / 3.0 + 2.0 / 3.0 * clamp(abs(fract(h + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
}
#endif

#ifndef DISTANCE_RANGE
#define DISTANCE_RANGE 4.0
#endif

float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}

void main() {
    vec2 unitRange = vec2(DISTANCE_RANGE) / vec2(textureSize(Sampler0, 0));
    vec2 screenTexSize = vec2(1.0) / fwidth(texCoord0);
    float screenPxRange = max(0.5 * dot(unitRange, screenTexSize), 1.0);

    vec3 msd = texture(Sampler0, texCoord0).rgb;
    float sd = median(msd.r, msd.g, msd.b);
#ifdef SOFT
    // A blurred copy for halos: fades over the whole stored distance band around the edge.
    float alpha = smoothstep(0.0, 0.75, sd);
#else
    float screenPxDistance = screenPxRange * (sd - 0.5);
    float alpha = clamp(screenPxDistance + 0.5, 0.0, 1.0);
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
