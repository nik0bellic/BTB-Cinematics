#version 330
#extension GL_ARB_separate_shader_objects : require


uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform HeatConfig {
    float Strength;
    float Top;
    float Phase;
};

layout(location = 0) out vec4 fragColor;

// Heat shimmer: tiny, slow, rising waves that are strongest near the bottom of the frame (ground)
// and vanish towards the top. No colour change – the air just "boils".
void main() {
    float t = Phase;
    float w = smoothstep(Top, 1.0, 1.0 - texCoord.y);
    vec2 uv = texCoord;
    float y = texCoord.y * OutSize.y;
    float x = texCoord.x * OutSize.x;
    // fine, fast ripples (real heat haze), a little slow drift on top; amplitude in pixels
    float px = sin(y * 0.42 + t * 5.0 + sin(x * 0.05) * 1.5) * 0.55 + sin(y * 0.23 - t * 3.0 + x * 0.03) * 0.35 + sin(y * 0.07 + t * 1.0) * 0.1;
    float py = sin(x * 0.31 + t * 4.0 + y * 0.02) * 0.5;
    uv += vec2(px, py * 0.5) * Strength * w / OutSize;
    fragColor = vec4(texture(InSampler, clamp(uv, 0.001, 0.999)).rgb, 1.0);
}
