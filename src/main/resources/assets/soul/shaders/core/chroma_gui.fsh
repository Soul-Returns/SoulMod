#version 330

// The chroma variant of vanilla core/gui.fsh: the rainbow at the interpolated hue parameter, alpha from the vertex.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

in vec4 vertexColor;
in float chromaHue;

out vec4 fragColor;

vec3 chromaRgb(float h) {
    return clamp(abs(fract(h + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
}

void main() {
    if (vertexColor.a == 0.0) {
        discard;
    }
    fragColor = vec4(chromaRgb(chromaHue), vertexColor.a) * ColorModulator;
}
