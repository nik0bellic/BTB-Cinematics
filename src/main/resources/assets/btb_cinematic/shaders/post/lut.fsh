#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;
uniform sampler2D LutSampler;

layout(location = 0) in vec2 texCoord;

layout(std140) uniform LutConfig {
    float Amount;
};

layout(location = 0) out vec4 fragColor;

// 3D colour lookup table (33³) laid out as 33 slices side by side (1089 × 33): a .cube grade from
// DaVinci Resolve / Premiere applied to the game picture.
const float N = 33.0;

vec3 lut(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    float b = c.b * (N - 1.0);
    float b0 = floor(b), b1 = min(N - 1.0, b0 + 1.0);
    float x = c.r * (N - 1.0) + 0.5, y = c.g * (N - 1.0) + 0.5;
    vec2 size = vec2(N * N, N);
    vec3 s0 = texture(LutSampler, vec2(b0 * N + x, y) / size).rgb;
    vec3 s1 = texture(LutSampler, vec2(b1 * N + x, y) / size).rgb;
    return mix(s0, s1, b - b0);
}

void main() {
    vec3 c = texture(InSampler, texCoord).rgb;
    fragColor = vec4(mix(c, lut(c), Amount), 1.0);
}
