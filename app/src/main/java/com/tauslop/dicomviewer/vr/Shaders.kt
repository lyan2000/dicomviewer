package com.tauslop.dicomviewer.vr

/** GLSL ES 3.00 sources for the ray caster. Geometry mirrors com.tauslop.dicom.vr.RayGeometry. */
internal object Shaders {

    /** Full-screen triangle from gl_VertexID; no vertex buffers needed. */
    const val VERTEX = """#version 300 es
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

    /**
     * Orthographic ray casting with front-to-back compositing.
     * Framebuffer row y holds image row y (top row first), so glReadPixels yields a top-down image.
     */
    const val FRAGMENT = """#version 300 es
precision highp float;
precision highp sampler3D;

uniform sampler3D u_vol;      // R16F, Hounsfield units
uniform sampler2D u_tf;       // N×1 RGBA8 transfer function
uniform mat4 u_texFromPatient;
uniform vec3 u_origin00;      // ray origin (patient mm) for pixel (0,0)
uniform vec3 u_dOriginX;      // origin step per pixel column
uniform vec3 u_dOriginY;      // origin step per pixel row (downwards)
uniform vec3 u_dir;           // unit ray direction (patient)
uniform float u_step;         // mm
uniform float u_refStep;      // mm the TF opacities are defined for
uniform float u_huMin;
uniform float u_huMax;
uniform float u_tfSize;
uniform float u_stopAlpha;

out vec4 fragColor;

// Per-pixel jitter of the first sample hides wood-grain banding from regular steps.
float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }

void main() {
    vec2 pix = gl_FragCoord.xy - 0.5;
    vec3 o = u_origin00 + u_dOriginX * pix.x + u_dOriginY * pix.y;
    vec3 to = (u_texFromPatient * vec4(o, 1.0)).xyz;
    vec3 td = (u_texFromPatient * vec4(u_dir, 0.0)).xyz;

    // Slab intersection with the unit box (texture space); t stays in mm. Exactly-zero direction
    // components (axis-aligned views) get a tiny epsilon so 0·inf can't produce NaN.
    vec3 inv = 1.0 / (td + vec3(1e-7) * (1.0 - abs(sign(td))));
    vec3 t0 = (vec3(0.0) - to) * inv;
    vec3 t1 = (vec3(1.0) - to) * inv;
    vec3 tmin = min(t0, t1);
    vec3 tmax = max(t0, t1);
    float tNear = max(max(max(tmin.x, tmin.y), tmin.z), 0.0);
    float tFar = min(min(tmax.x, tmax.y), tmax.z);
    if (tNear > tFar) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }

    vec4 acc = vec4(0.0);
    float t = tNear + u_step * hash(gl_FragCoord.xy);
    float tfScale = (u_tfSize - 1.0) / (u_huMax - u_huMin);
    float stepRatio = u_step / u_refStep;
    for (int i = 0; i < 4096; i++) {
        if (t > tFar || acc.a > u_stopAlpha) break;
        float hu = texture(u_vol, to + td * t).r;
        float idx = clamp((hu - u_huMin) * tfScale, 0.0, u_tfSize - 1.0);
        vec4 c = texture(u_tf, vec2((idx + 0.5) / u_tfSize, 0.5));
        float a = 1.0 - pow(1.0 - c.a, stepRatio);
        acc.rgb += (1.0 - acc.a) * a * c.rgb;
        acc.a += (1.0 - acc.a) * a;
        t += u_step;
    }
    fragColor = vec4(acc.rgb, 1.0); // composited over black
}
"""
}
