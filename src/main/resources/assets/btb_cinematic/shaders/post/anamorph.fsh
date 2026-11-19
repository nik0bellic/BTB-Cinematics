#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform AnamorphConfig {
    float Strength;
    float Threshold;
};

layout(location = 0) out vec4 fragColor;

// Anamorphic streaks: very bright points (lava, lamps, portal, fire) bleed into long, thin
// horizontal blue-white lines, like a cinema lens.
void main() {
    vec3 base = texture(InSampler, texCoord).rgb;
    vec3 streak = vec3(0.0);
    float step = 7.0 / OutSize.x;
    for (int i = -28; i <= 28; i++) {
        if (i == 0) continue;
        vec3 s = texture(InSampler, texCoord + vec2(float(i) * step, 0.0)).rgb;
        float l = dot(s, vec3(0.299, 0.587, 0.114));
        float b = max(0.0, l - Threshold) / (1.0 - Threshold);
        streak += s * b * b * exp(-abs(float(i)) / 9.0);
    }
    streak *= vec3(0.5, 0.72, 1.2) * Strength / 9.0;
    fragColor = vec4(base + streak, 1.0);
}
