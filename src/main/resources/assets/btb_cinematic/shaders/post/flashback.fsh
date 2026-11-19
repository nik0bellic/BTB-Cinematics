#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform FlashbackConfig {
    float Amount;
    float Phase;
};

layout(location = 0) out vec4 fragColor;

// Flashback / memory: washed-out warm colours, lifted blacks, soft dreamy edges and a slow,
// gentle drift of the picture.
void main() {
    vec2 uv = texCoord;
    uv.x += sin(texCoord.y * 6.0 + Phase) * 0.0016 * Amount;
    uv.y += sin(texCoord.x * 5.0 + Phase * 2.0) * 0.0012 * Amount;
    vec2 d = texCoord - 0.5;
    float edge = smoothstep(0.25, 0.75, length(d * vec2(1.25, 1.0)));
    vec3 c = texture(InSampler, uv).rgb;
    vec2 px = 1.0 / OutSize;
    vec3 soft = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.785398;
        soft += texture(InSampler, uv + vec2(cos(a), sin(a)) * px * 4.0).rgb;
    }
    soft /= 8.0;
    c = mix(c, soft, edge * Amount);
    float lum = dot(c, vec3(0.299, 0.587, 0.114));
    vec3 warm = vec3(lum) * vec3(1.08, 0.98, 0.82);
    c = mix(c, warm, 0.75 * Amount);
    c = c * (1.0 - 0.18 * Amount) + 0.08 * Amount;
    c *= 1.0 - edge * 0.45 * Amount;
    fragColor = vec4(c, 1.0);
}
