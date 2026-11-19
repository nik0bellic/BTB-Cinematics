#version 330
#extension GL_ARB_separate_shader_objects : require


uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform GrainConfig {
    float Amount;
    float Edge;
    float Seed;
};

layout(location = 0) out vec4 fragColor;

// Film grain: luminance noise that changes every frame, stronger in the mid tones (like real stock),
// plus a very soft optical edge falloff and a touch of desaturation. Subtle on purpose.
float hash(vec2 p) {
    p = fract(p * vec2(443.897, 441.423));
    p += dot(p, p.yx + 19.19);
    return fract((p.x + p.y) * p.x);
}

void main() {
    vec4 c = texture(InSampler, texCoord);
    float t = Seed;
    vec2 px = floor(texCoord * OutSize / 1.5);
    float n = hash(px + vec2(t * 13.7, t * 7.3)) + hash(px * 1.7 + vec2(t * 3.1, -t * 5.9)) - 1.0;
    float lum = dot(c.rgb, vec3(0.299, 0.587, 0.114));
    float mid = 1.0 - abs(lum * 2.0 - 1.0);
    vec3 col = c.rgb + n * Amount * (0.35 + 0.65 * mid);
    col = mix(vec3(lum), col, 1.0 - Amount * 0.6);
    vec2 d = texCoord - 0.5;
    float fall = 1.0 - Edge * smoothstep(0.35, 0.85, length(d * vec2(1.2, 1.0)));
    fragColor = vec4(col * fall, 1.0);
}
