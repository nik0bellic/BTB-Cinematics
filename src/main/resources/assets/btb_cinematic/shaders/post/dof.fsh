#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

layout(location = 0) in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform DofConfig {
    float Focus;    // focus distance in blocks
    float MaxBlur;  // max blur radius in pixels
};

layout(location = 0) out vec4 fragColor;

// Depth of field: the sharp plane sits at Focus, everything nearer or farther dissolves into soft
// bokeh. Distance comes from the real depth buffer (near plane 0.05, far plane assumed large).
float lin(float d) {
    // Minecraft 26.3 uses reversed Z (near = 1, far = 0)
    float n = 0.05, f = 768.0;
    return n * f / (n + d * (f - n));
}

float coc(vec2 uv) {
    float z = lin(texture(DepthSampler, uv).r);
    float range = max(1.0, Focus * 0.35);
    return clamp(abs(z - Focus) / range - 0.15, 0.0, 1.0);
}

void main() {
    vec2 px = 1.0 / OutSize;
    float c = coc(texCoord);
    vec3 sum = texture(InSampler, texCoord).rgb;
    float wsum = 1.0;
    float r = c * MaxBlur;
    if (r > 0.5) {
        // golden-angle disc, 24 taps; samples that are themselves sharp (in focus) count less,
        // so a sharp actor does not bleed into the blurred background
        for (int i = 1; i <= 24; i++) {
            float a = float(i) * 2.39996;
            float d = sqrt(float(i) / 24.0) * r;
            vec2 o = vec2(cos(a), sin(a)) * d * px;
            float sc = coc(texCoord + o);
            float w = mix(0.25, 1.0, sc);
            sum += texture(InSampler, texCoord + o).rgb * w;
            wsum += w;
        }
    }
    fragColor = vec4(sum / wsum, 1.0);
}
