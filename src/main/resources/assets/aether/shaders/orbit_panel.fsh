#version 330 core

in vec2 texCoord;

uniform sampler2D Panel;
uniform vec4 Tint;
uniform float Dim;
// the share of the texture the panel fills, and its corner radius as a share of its width and height
uniform vec2 UvMax;
uniform vec2 Corner;

out vec4 fragColor;

// panel textures hold premultiplied alpha straight out of nanovg; the corner mask rounds whatever was drawn,
// headers and scrolled content included, with an edge as soft as one screen pixel
void main() {
    vec4 c = texture(Panel, texCoord * UvMax);
    c.rgb *= 1.0 - Dim;
    float mask = 1.0;
    if (Corner.x > 0.0 && Corner.y > 0.0) {
        vec2 q = vec2(max(max(Corner.x - texCoord.x, texCoord.x - (1.0 - Corner.x)), 0.0) / Corner.x,
                      max(max(Corner.y - texCoord.y, texCoord.y - (1.0 - Corner.y)), 0.0) / Corner.y);
        float d = length(q);
        float edge = max(fwidth(d), 1e-4);
        mask = 1.0 - smoothstep(1.0 - edge, 1.0 + edge, d);
    }
    fragColor = c * Tint * mask;
}
