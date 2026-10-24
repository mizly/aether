#version 330

layout(std140) uniform SkyboxInfo {
    mat4 InverseViewProjection;
    int Preset;
    float Time;
    float Brightness;
};
out vec3 direction;

void main() {
    vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vec2 clip = corner * 2.0 - 1.0;
    direction = (InverseViewProjection * vec4(clip, 1.0, 1.0)).xyz;
    gl_Position = vec4(clip, 1.0, 1.0);
}
