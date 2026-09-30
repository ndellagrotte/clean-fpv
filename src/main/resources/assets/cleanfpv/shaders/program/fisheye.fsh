#version 120

// Clean FPV: equidistant-style barrel distortion of the rendered frame.
//
// Distances are measured in picture-height units from the centre (x scaled by Aspect). A pixel at
// distance d is treated as looking along the ray whose rectilinear image lies at d relative to the
// pivot distance sqrt(0.5); its source is pushed outwards by tan(t)/t (t = that ray's angle) and
// the whole picture is scaled by h/tan(h) (h = half the vertical FOV) so the pivot stays put.
// Samples that land outside the source frame are painted black.

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform float FovY;
uniform float Aspect;

varying vec2 screenUv;

const float PIVOT = 0.70710678;

void main() {
    vec2 offset = screenUv - vec2(0.5);
    float halfFov = clamp(FovY * 0.5, 0.01, 1.55);
    float tanHalf = tan(halfFov);

    float dist = length(vec2(offset.x * Aspect, offset.y));
    float ray = atan(dist * tanHalf / PIVOT);
    float spread = ray > 1.0e-4 ? tan(ray) / ray : 1.0;
    vec2 source = vec2(0.5) + offset * (spread * halfFov / tanHalf);

    vec2 margin = 0.5 / max(InSize, vec2(1.0));
    bool inside = source.x >= -margin.x && source.y >= -margin.y
               && source.x <= 1.0 + margin.x && source.y <= 1.0 + margin.y;

    vec3 colour = inside ? texture2D(DiffuseSampler, source).rgb : vec3(0.0);
    gl_FragColor = vec4(colour, 1.0);
}
