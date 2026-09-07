#version 330 core
layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV;
layout(location = 2) in vec4 Color;
layout(location = 3) in vec3 Effect;
uniform mat4 ViewProjection;
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
