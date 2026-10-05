#version 330 core

in vec2 texCoord;
in vec4 vertexColor;
in float fogDistance;

uniform sampler2D Sampler;
uniform vec2 FogRange;
uniform vec3 FogColor;
uniform float AlphaCut;
uniform float Solid;

out vec4 fragColor;

// the clone fades into the sky at its rim, so it reads as a floating island rather than a cut-off world
void main() {
    vec4 c = texture(Sampler, texCoord) * vertexColor;
    if (c.a < AlphaCut) discard;
    float fog = smoothstep(FogRange.x, FogRange.y, fogDistance);
    // opaque passes keep the target's alpha whole, only water blends
    float alpha = Solid > 0.5 ? 1.0 : c.a * (1.0 - fog * fog);
    fragColor = vec4(mix(c.rgb, FogColor, fog), alpha);
}
