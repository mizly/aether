#version 330 core

in vec2 texCoord;

uniform sampler2D Panel;
uniform vec4 Tint;
uniform float Dim;

out vec4 fragColor;

// panel textures hold premultiplied alpha straight out of nanovg
void main() {
    vec4 c = texture(Panel, texCoord);
    c.rgb *= 1.0 - Dim;
    fragColor = c * Tint;
}
