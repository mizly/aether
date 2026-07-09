#version 150 core

uniform sampler2D Scene;
uniform int   NumRipples;
uniform vec2  Centers[4];      // UV [0,1] x [0,1], Y-up (OpenGL convention)
uniform float Radii[4];        // ring radius  / screen height
uniform float Thicknesses[4];  // ring halfwidth / screen height
uniform float Strengths[4];    // [0, 1]
uniform float Aspect;          // width / height

in  vec2 vUV;
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
    vec4  result      = texture(Scene, vUV);
    float usedStrength = 0.0;

    for (int r = 0; r < 4; r++) {
        if (r >= NumRipples) break;

        // Aspect-correct distance so the ring is circular (not oval).
        vec2  d    = vUV - Centers[r];
        d.x *= Aspect;
        float dist = length(d);

        // Radial unit vector in UV space.
        vec2 radialDir = (dist > 1e-5) ? (d / dist) : vec2(1.0, 0.0);
        radialDir.x /= Aspect;

        // Fill the entire interior: full effect inside the ring, soft outer falloff.
        // Removing abs() means pixels inside (dist < radius) clamp to 1.0,
        // so blur covers the whole disk rather than just the ring edge.
        float t = clamp(1.0 - (dist - Radii[r]) / max(Thicknesses[r], 1e-5), 0.0, 1.0);
        t = t * t * (3.0 - 2.0 * t); // smoothstep for softer outer edge

        float effect = t * Strengths[r] * (1.0 - usedStrength);
        if (effect < 0.004) continue;

        // Outward lens displacement (very subtle).
        vec2  displaced = vUV + radialDir * (effect * DISP_SCALE);

        // Gaussian blur along the radial axis.
        vec4  blurred = radialBlur(displaced, radialDir, effect * BLUR_SCALE);

        // Minimal luminance tint — almost colour-neutral.
        blurred.rgb += vec3(GLOW * effect);

        result      = mix(result, blurred, effect);
        usedStrength = min(usedStrength + effect, 1.0);
    }

    outColor = result;
}
