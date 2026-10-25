#version 330

uniform sampler2D Scene;

// per ripple: xy centre in uv (y up), z radius and w half-width, both over screen height
layout(std140) uniform RippleInfo {
    vec4 Rings[4];
    vec4 Strengths;
    int NumRipples;
    float Aspect;
};

in vec2 texCoord;
out vec4 outColor;

// Blur kernel size relative to screen height. Scales with effect strength.
const float BLUR_SCALE = 0.070;
// Displacement (lens distortion) at peak strength — kept subtle.
const float DISP_SCALE = 0.025;
// Additive white glow at peak strength — near-zero for colour neutrality.
const float GLOW       = 0.14;

const int SAMPLES = 13;

// 1-D Gaussian blur along dir by amount (in UV space).
vec4 radialBlur(vec2 uv, vec2 dir, float amount) {
    vec4  col  = vec4(0.0);
    float wtot = 0.0;
    for (int i = 0; i < SAMPLES; i++) {
        float t = (float(i) / float(SAMPLES - 1) - 0.5) * 2.0; // [-1, 1]
        float w = exp(-3.5 * t * t);
        col  += texture(Scene, uv + dir * (t * amount)) * w;
        wtot += w;
    }
    return col / wtot;
}

void main() {
    vec4  result      = texture(Scene, texCoord);
    float usedStrength = 0.0;

    for (int r = 0; r < 4; r++) {
        if (r >= NumRipples) break;

        // Aspect-correct distance so the ring is circular (not oval).
        vec2  d    = texCoord - Rings[r].xy;
        d.x *= Aspect;
        float dist = length(d);

        // Radial unit vector in UV space.
        vec2 radialDir = (dist > 1e-5) ? (d / dist) : vec2(1.0, 0.0);
        radialDir.x /= Aspect;

        // Fill the entire interior: full effect inside the ring, soft outer falloff.
        // Removing abs() means pixels inside (dist < radius) clamp to 1.0,
        // so blur covers the whole disk rather than just the ring edge.
        float t = clamp(1.0 - (dist - Rings[r].z) / max(Rings[r].w, 1e-5), 0.0, 1.0);
        t = t * t * (3.0 - 2.0 * t); // smoothstep for softer outer edge

        float effect = t * Strengths[r] * (1.0 - usedStrength);
        if (effect < 0.004) continue;

        // Outward lens displacement (very subtle).
        vec2  displaced = texCoord + radialDir * (effect * DISP_SCALE);

        // Gaussian blur along the radial axis.
        vec4  blurred = radialBlur(displaced, radialDir, effect * BLUR_SCALE);

        // Minimal luminance tint — almost colour-neutral.
        blurred.rgb += vec3(GLOW * effect);

        result      = mix(result, blurred, effect);
        usedStrength = min(usedStrength + effect, 1.0);
    }

    outColor = result;
}
