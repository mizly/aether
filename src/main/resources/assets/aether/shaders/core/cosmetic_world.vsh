#version 330
#extension GL_ARB_separate_shader_objects : require

layout(std140) uniform CosmeticInfo {
    mat4 ViewProjection;
};

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV;
layout(location = 2) in vec4 Color;
layout(location = 3) in vec3 Effect;
layout(location = 0) out vec2 uv;
layout(location = 1) out vec4 color;
layout(location = 2) flat out int material;
layout(location = 3) out float age;
layout(location = 4) out float seed;
void main() {
    gl_Position = ViewProjection * vec4(Position, 1.0);
    uv = UV;
    color = Color;
    material = int(Effect.x + 0.5);
    age = Effect.y;
    seed = Effect.z;
}
