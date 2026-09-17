#version 330 core
in vec2 uv;
in vec4 color;
flat in int material;
in float age;
in float seed;
out vec4 fragColor;

float band(float distance, float width) {
    return 1.0 - smoothstep(width, width + max(fwidth(distance), 0.003), abs(distance));
}

void main() {
    if (material == 0) {
        // Fine stretched fibers and a quiet pulse keep the membrane readable at a distance.
        float edge = pow(abs(uv.x * 2.0 - 1.0), 14.0);
        float fibers = pow(0.5 + 0.5 * sin(uv.y * 64.0 + uv.x * 13.0), 12.0);
        float sheen = 0.5 + 0.5 * sin(uv.y * 5.0 - age);
        vec3 skin = mix(vec3(0.026, 0.018, 0.043), color.rgb * 0.28, 0.35 + uv.y * 0.4);
        skin += color.rgb * (edge * 0.22 + fibers * 0.025 + sheen * 0.04) * seed;
        fragColor = vec4(skin, color.a);
        return;
    }
    if (material == 1) {
        fragColor = color;
        return;
    }
    if (material == 6 || material == 7) {
        float distance = material == 6 ? abs(uv.y) : abs(uv.x) + abs(uv.y);
        float core = band(distance, material == 6 ? 0.13 : 0.22);
        float glow = pow(max(0.0, 1.0 - distance), 3.0) * seed * 0.45;
        float ends = material == 6 ? smoothstep(0.0, 0.045, uv.x) * smoothstep(0.0, 0.045, 1.0 - uv.x) : 1.0;
        float alpha = min(1.0, core + glow) * color.a * ends;
        if (alpha < 0.003) discard;
        fragColor = vec4(mix(color.rgb, vec3(1.0), core * 0.22), alpha);
        return;
    }
    float r = length(uv);
    float alpha = 0.0;
    vec3 tint = color.rgb;
    if (material == 5) {
        float diamond = abs(uv.x) + abs(uv.y);
        alpha = (1.0 - smoothstep(0.35, 0.8, diamond)) * (0.55 + 0.45 * cos(seed + age * 9.0));
        tint = mix(tint, vec3(1.0), (1.0 - smoothstep(0.0, 0.24, diamond)) * 0.7);
    } else {
        if (r > 1.0) discard;
        float angle = atan(uv.y, uv.x);
        float ring = 0.12 + 0.7 * (1.0 - pow(1.0 - age, 3.0));
        float opening = smoothstep(0.0, 0.08, age);
        float fading = 1.0 - smoothstep(0.28, 1.0, age);
        if (material == 2) {
            float spiral = angle * 3.0 + r * 15.0 - age * 10.0 + seed;
            float arc = pow(0.5 + 0.5 * sin(spiral), 9.0);
            alpha = band(r - ring, 0.014) * 0.85
                    + exp(-abs(r - ring) * 23.0) * arc * 0.65
                    + exp(-r * 10.0) * (1.0 - age) * 0.5;
            tint = mix(tint, vec3(0.91, 0.77, 1.0), band(r - ring, 0.005) * 0.65);
        } else if (material == 3) {
            float petal = ring * (0.74 + 0.19 * cos(angle * 6.0 + age * 1.3));
            alpha = band(r - petal, 0.012) * 0.9 + band(r - ring, 0.009) * 0.7;
            alpha += band(r - ring * 0.48, 0.008) * 0.45;
            float glints = pow(abs(cos(angle * 6.0 + age * 1.3)), 32.0);
            alpha += exp(-abs(r - ring) * 19.0) * glints * 0.65;
            tint = mix(tint, vec3(0.8, 1.0, 1.0), glints * 0.65);
        } else {
            float rays = pow(0.5 + 0.5 * sin(angle * 11.0 + sin(angle * 5.0 + seed)), 6.0);
            float corona = ring + rays * 0.055 * (1.0 - age);
            alpha = exp(-r * (9.0 + age * 12.0)) * 1.9 * (1.0 - age)
                    + exp(-abs(r - corona) * 32.0) * (0.35 + rays * 0.7);
            tint = mix(tint, vec3(1.0, 0.94, 0.63), exp(-r * 7.0));
        }
        alpha *= opening * fading;
    }
    alpha *= color.a;
    if (alpha < 0.003) discard;
    fragColor = vec4(tint, min(alpha, 1.0));
}
