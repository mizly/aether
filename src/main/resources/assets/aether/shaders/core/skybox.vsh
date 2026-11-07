#version 330
#extension GL_ARB_separate_shader_objects : require

layout(std140) uniform SkyboxInfo {
    mat4 InverseViewProjection;
    int Preset;
    float Time;
    float Brightness;
};
layout(location = 0) out vec3 direction;

void main() {
    vec2 corner = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));
    vec2 clip = corner * 2.0 - 1.0;
    direction = (InverseViewProjection * vec4(clip, 1.0, 1.0)).xyz;
    gl_Position = vec4(clip, 1.0, 1.0);
}
