#version 330

// The chroma variant of vanilla core/position_tex_color.fsh (vertex shader core/msdf_text with CHROMA): a tinted
// texture whose tint is the rainbow at the interpolated hue parameter, alpha from the vertex.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec4 vertexColor;
in float chromaHue;

out vec4 fragColor;

// Chroma palette of mocks/scoreboard: FF5555 FFFF55 55FF55 55FFFF 5555FF FF55FF, linear between stops, wrapping.
// Identical in every Soul shader and equal to Kotlin's Chroma.rainbow (ChromaTest checks both).
vec3 chromaRgb(float h) {
    return 1.0 / 3.0 + 2.0 / 3.0 * clamp(abs(fract(h + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
}

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vec4(chromaRgb(chromaHue), vertexColor.a);
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}
