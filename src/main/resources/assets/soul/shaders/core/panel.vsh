#version 330

// Soul's rounded panel (config/gui/render/SoulPanel.kt): a fill and an optional ring of any radius in one quad, every
// parameter per vertex so panels of any shape batch in one draw. Vertex format SoulPanel.FORMAT:
//   Position  xy = GUI position, z = the chroma hue parameter (Chroma.kt), never depth
//   UV0       signed composable-unit coordinates relative to the quad center (as core/round_rect)
//   Color     fill color
//   UV1       ring color, RGBA8 packed into two shorts (RG, BA); with no ring: the clip box's x0, y0
//   UV2       second ring color, packed the same way; with no ring: the clip box's x1, y1
//             (clip box: SoulPanel.slice, in eighth units relative to the quad center, as UV0)
//   Normal    x = radius * 2 (127 = pill), y = ring width * 4, z = rounded corners (1 TL, 2 TR, 4 BR, 8 BL), each a
//             whole number over 127
//   LineWidth ring gradient phase in turns, negative = one ring color
// Inlined UBOs: shaders compile before resource packs exist, so they cannot #moj_import.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;
in float LineWidth;

out vec2 texCoord0;
out float chromaHue;
flat out vec2 halfSize;
flat out vec4 fillColor;
flat out vec4 ringColor;
flat out vec4 ringColor2;
// x = fill, y = ring, z = second ring color: 1 when that color is chroma (Chroma.MARK).
flat out vec3 chromaOn;
flat out vec3 shape; // radius (negative = pill), ring width, gradient phase (negative = none)
flat out int corners;
flat out vec4 clipBox;

vec4 unpack(ivec2 v) {
    ivec2 u = v & 0xFFFF;
    return vec4(float(u.x >> 8), float(u.x & 255), float(u.y >> 8), float(u.y & 255)) / 255.0;
}

float marked(vec4 c) {
    ivec3 b = ivec3(round(c.rgb * 255.0));
    return (b == ivec3(0x5B, 0x1D, 0x8E)) ? 1.0 : 0.0;
}

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position.xy, 0.0, 1.0);
    chromaHue = Position.z;
    texCoord0 = UV0;
    halfSize = abs(UV0);
    fillColor = Color;
    ringColor = unpack(UV1);
    ringColor2 = unpack(UV2);
    chromaOn = vec3(marked(fillColor), marked(ringColor), marked(ringColor2));
    clipBox = vec4(UV1, UV2) * 0.125;
    float r = round(Normal.x * 127.0);
    shape = vec3(r >= 127.0 ? -1.0 : r * 0.5, round(Normal.y * 127.0) * 0.25, LineWidth);
    corners = int(round(Normal.z * 127.0));
}
