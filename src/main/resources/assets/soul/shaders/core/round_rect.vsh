#version 330

// Mirrors vanilla core/position_tex_color.vsh (inlined UBOs — can't #moj_import during early
// startup before resource packs exist). Vertex format: POSITION_TEX_COLOR. UV0 carries SIGNED
// composable-unit coordinates relative to the quad center (so the fragment SDF works in composable
// units and the corner radius scales with the UI). abs(UV0) at every corner equals the composable
// half-size, forwarded to the fragment as a flat varying.
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

out vec2 texCoord0;
flat out vec2 halfSize;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord0 = UV0;
    halfSize = abs(UV0);
    vertexColor = Color;
}
