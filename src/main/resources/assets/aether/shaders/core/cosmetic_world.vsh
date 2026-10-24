#version 330

layout(std140) uniform CosmeticInfo {
    mat4 ViewProjection;
};

in vec3 Position;
in vec2 UV;
in vec4 Color;
in vec3 Effect;
out vec2 uv;
out vec4 color;
flat out int material;
out float age;
out float seed;
void main() {
    gl_Position = ViewProjection * vec4(Position, 1.0);
    uv = UV;
    color = Color;
    material = int(Effect.x + 0.5);
    age = Effect.y;
    seed = Effect.z;
}
