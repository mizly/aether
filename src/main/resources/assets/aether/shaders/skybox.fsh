#version 330 core

uniform int Preset;
uniform float Time;
uniform float Brightness;
in vec3 direction;
out vec4 fragColor;

float hash(vec2 p) {
    vec3 h = fract(vec3(p.xyx) * 0.1031);
    h += dot(h, h.yzx + 33.33);
    return fract((h.x + h.y) * h.z);
}

vec3 hash3(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx);
}

float noise(vec2 p) {
    vec2 cell = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(cell), hash(cell + vec2(1.0, 0.0)), f.x),
               mix(hash(cell + vec2(0.0, 1.0)), hash(cell + 1.0), f.x), f.y);
}

float cloudNoise(vec2 p) {
    return noise(p) * 0.57 + noise(p * 2.03 + 13.7) * 0.29 + noise(p * 4.11 + 27.3) * 0.14;
}

float clouds(vec3 d, float stretch) {
    vec2 p = d.xz / (0.30 + max(d.y, 0.0));
    p = p * vec2(2.0, stretch) + vec2(Time * 0.013, Time * 0.004);
    return smoothstep(0.43, 0.78, cloudNoise(p)) * smoothstep(0.0, 0.18, d.y);
}

vec3 stars(vec3 d) {
    vec3 p = d * 125.0;
    vec3 seed = hash3(floor(p));
    vec3 offset = fract(p) - (0.18 + seed * 0.64);
    float distance = length(offset);
    float radius = 0.07 + seed.z * 0.055;
    float edge = max(length(fwidth(p)) * 0.45, 0.012);
    float point = (1.0 - smoothstep(max(radius - edge, 0.0), radius + edge, distance))
            * radius / (radius + edge);
    float twinkle = 0.88 + 0.12 * sin(Time * 0.65 + seed.x * 60.0);
    return mix(vec3(0.64, 0.77, 1.0), vec3(1.0, 0.91, 0.76), seed.x)
            * point * step(0.72, seed.y) * twinkle * smoothstep(0.0, 0.18, d.y);
}

vec3 cirrus(vec3 d) {
    float altitude = pow(max(d.y, 0.0), 0.55);
    vec3 sky = mix(vec3(0.71, 0.83, 0.89), vec3(0.16, 0.39, 0.66), altitude);
    float glow = pow(max(dot(d, normalize(vec3(-0.6, 0.45, -0.7))), 0.0), 16.0);
    sky += vec3(0.12, 0.10, 0.06) * glow;
    sky = mix(sky, vec3(0.91, 0.94, 0.95), clouds(d, 7.0) * 0.78);
    return mix(vec3(0.56, 0.69, 0.78), sky, smoothstep(-0.65, 0.0, d.y));
}

vec3 goldenHour(vec3 d) {
    float altitude = smoothstep(-0.02, 0.85, d.y);
    vec3 sky = mix(vec3(0.91, 0.59, 0.37), vec3(0.27, 0.40, 0.61), altitude);
    vec3 sun = normalize(vec3(-0.48, 0.12, -0.87));
    float alignment = max(dot(d, sun), 0.0);
    sky += vec3(0.22, 0.17, 0.07) * pow(alignment, 18.0);
    float sunDisc = smoothstep(0.9990, 0.9994, alignment);
    sky = mix(sky, vec3(1.0, 0.91, 0.65), sunDisc * smoothstep(-0.03, 0.02, d.y));
    vec3 cloudColor = mix(vec3(0.67, 0.46, 0.46), vec3(1.0, 0.79, 0.57), pow(alignment, 3.0));
    sky = mix(sky, cloudColor, clouds(d, 4.0) * 0.60);
    return mix(vec3(0.48, 0.36, 0.38), sky, smoothstep(-0.55, 0.03, d.y));
}

vec3 twilight(vec3 d) {
    float altitude = pow(max(d.y, 0.0), 0.65);
    vec3 sky = mix(vec3(0.69, 0.40, 0.43), vec3(0.075, 0.12, 0.25), altitude);
    sky += vec3(0.065, 0.045, 0.11) * exp2(-abs(d.y - 0.25) * 9.0);
    float cloud = clouds(d, 5.0) * 0.42;
    sky = mix(sky, vec3(0.42, 0.36, 0.49), cloud);
    sky += stars(d) * smoothstep(0.15, 0.65, d.y) * (1.0 - cloud) * 0.7;
    return mix(vec3(0.20, 0.19, 0.28), sky, smoothstep(-0.50, 0.0, d.y));
}

vec3 aurora(vec3 d) {
    vec3 sky = mix(vec3(0.065, 0.14, 0.19), vec3(0.018, 0.033, 0.085), max(d.y, 0.0));
    float phase = d.x * 3.5 + d.z * 2.2;
    float bend = sin(phase + Time * 0.045) * 0.11 + sin(phase * 2.7 - Time * 0.025) * 0.04;
    float ribbon = d.y - 0.30 - bend;
    float secondRibbon = d.y - 0.53 - bend * 0.60;
    float folds = 0.55 + noise(vec2(d.x * 65.0 + d.z * 41.0 - Time * 0.06, d.y * 1.5)) * 0.45;
    float curtain = exp2(-max(ribbon, 0.0) * 13.0 - max(-ribbon, 0.0) * 75.0) * folds;
    float secondCurtain = exp2(-abs(secondRibbon) * 28.0) * 0.28;
    vec3 color = mix(vec3(0.12, 0.52, 0.32), vec3(0.37, 0.23, 0.62), smoothstep(0.01, 0.17, ribbon));
    sky += (color * curtain + vec3(0.12, 0.37, 0.48) * secondCurtain) * smoothstep(0.02, 0.22, d.y);
    sky += stars(d) * 0.75;
    return mix(vec3(0.04, 0.075, 0.10), sky, smoothstep(-0.5, 0.0, d.y));
}

vec3 starfield(vec3 d) {
    vec3 sky = mix(vec3(0.085, 0.12, 0.19), vec3(0.013, 0.022, 0.058), pow(max(d.y, 0.0), 0.5));
    float band = pow(max(1.0 - abs(dot(d, normalize(vec3(0.45, 0.55, 0.70)))), 0.0), 18.0);
    float dust = cloudNoise(d.xz * 6.0 + d.y * 2.0);
    sky += vec3(0.045, 0.051, 0.075) * band * (0.35 + dust) * smoothstep(0.0, 0.25, d.y);
    sky += stars(d);
    return mix(vec3(0.03, 0.045, 0.075), sky, smoothstep(-0.5, 0.0, d.y));
}

void main() {
    vec3 d = normalize(direction);
    vec3 color;
    if (Preset == 0) color = cirrus(d);
    else if (Preset == 1) color = goldenHour(d);
    else if (Preset == 2) color = twilight(d);
    else if (Preset == 3) color = aurora(d);
    else color = starfield(d);
    float dither = (hash(gl_FragCoord.xy) - 0.5) / 255.0;
    fragColor = vec4(clamp(color * Brightness + dither, 0.0, 1.0), 1.0);
}
