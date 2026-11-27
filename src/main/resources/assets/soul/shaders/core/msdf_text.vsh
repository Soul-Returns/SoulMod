#version 330

// Copy of core/position_tex_color.vsh — POSITION_TEX_COLOR, passes atlas UV + color through.
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
out vec4 vertexColor;
#ifdef CHROMA
// Screen-space chroma (config/gui/render/Chroma.kt): Position.z carries the hue parameter, not depth.
out float chromaHue;
#endif

void main() {
#ifdef CHROMA
    gl_Position = ProjMat * ModelViewMat * vec4(Position.xy, 0.0, 1.0);
    chromaHue = Position.z;
#else
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
#endif
    texCoord0 = UV0;
    vertexColor = Color;
}
