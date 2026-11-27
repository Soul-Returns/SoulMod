#version 330

// The chroma variant of vanilla core/gui.vsh (POSITION_COLOR; inlined UBOs, as vanilla's own copy): Soul's shapes and
// fills in a chroma color (config/gui/render/Chroma.kt). Position.z carries the hue parameter, not depth.
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
in vec4 Color;

out vec4 vertexColor;
out float chromaHue;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position.xy, 0.0, 1.0);
    chromaHue = Position.z;
    vertexColor = Color;
}
