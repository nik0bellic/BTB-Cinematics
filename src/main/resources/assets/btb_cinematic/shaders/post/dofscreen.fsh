#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform DofScreenConfig {
    float CenterX;
    float CenterY;
    float Radius;
    float MaxBlur;
};

layout(location = 0) out vec4 fragColor;

// Depth of field for shader packs (they keep the depth buffer to themselves): the area around the
// subject stays sharp, the picture softens smoothly towards the edges – like a fast lens wide open.
void main() {
    vec2 px = 1.0 / OutSize;
    float aspect = OutSize.x / OutSize.y;
    vec2 d = (texCoord - vec2(CenterX, CenterY)) * vec2(aspect, 1.0);
    float c = smoothstep(Radius, Radius * 2.4, length(d));
    vec3 sum = texture(InSampler, texCoord).rgb;
    float wsum = 1.0;
    float r = c * MaxBlur;
    if (r > 0.5) {
        for (int i = 1; i <= 24; i++) {
            float a = float(i) * 2.39996;
            float dd = sqrt(float(i) / 24.0) * r;
            sum += texture(InSampler, texCoord + vec2(cos(a), sin(a)) * dd * px).rgb;
            wsum += 1.0;
        }
    }
    fragColor = vec4(sum / wsum, 1.0);
}
