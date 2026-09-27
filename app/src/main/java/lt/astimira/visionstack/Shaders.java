package lt.astimira.visionstack;

/**
 * Three genuinely composited processing stages: camera to FBO,
 * two independent direction-specific approximate inverse filters,
 * and a luminance-only tone/local-contrast stage.
 * Experimental display prefilter, not calibrated optical correction.
 */
public final class Shaders {
    private Shaders() {}
    public static final String VERT = """
        attribute vec2 aPos;
        attribute vec2 aUv;
        varying vec2 vUv;
        void main() {
            vUv = aUv;
            gl_Position = vec4(aPos,0.0,1.0);
        }
        """;
    public static final String CAMERA = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        varying vec2 vUv;
        uniform samplerExternalOES uImage;
        uniform mat4 uMatrix;
        uniform float uTurns;
        uniform vec2 uCrop;
        void main() {
            vec2 q = (vUv - 0.5) * uCrop + 0.5;
            q.x = 1.0 - q.x; /* front-facing video mirror */
            if (uTurns > 0.5 && uTurns < 1.5) q = vec2(q.y, 1.0-q.x);
            else if (uTurns > 1.5 && uTurns < 2.5) q = vec2(1.0-q.x,1.0-q.y);
            else if (uTurns > 2.5) q = vec2(1.0-q.y,q.x);
            vec2 st = (uMatrix * vec4(q,0.0,1.0)).xy;
            gl_FragColor = texture2D(uImage, st);
        }
        """;
    public static final String DIRECTIONAL = """
        precision highp float;
        varying vec2 vUv;
        uniform sampler2D uImage;
        uniform vec2 uStep;
        uniform float uGain;
        float lum(vec3 c) { return dot(c,vec3(0.2126,0.7152,0.0722)); }
        void main() {
            vec3 c = texture2D(uImage,vUv).rgb;
            vec3 p1 = texture2D(uImage,clamp(vUv+uStep,0.0,1.0)).rgb;
            vec3 m1 = texture2D(uImage,clamp(vUv-uStep,0.0,1.0)).rgb;
            vec3 p2 = texture2D(uImage,clamp(vUv+2.0*uStep,0.0,1.0)).rgb;
            vec3 m2 = texture2D(uImage,clamp(vUv-2.0*uStep,0.0,1.0)).rgb;
            vec3 blurred = 0.32*c + 0.24*(p1+m1) + 0.10*(p2+m2);
            /* Bounded luminance-domain first-order approximate inverse. */
            float correction = clamp(uGain*(lum(c)-lum(blurred)),-0.30,0.30);
            gl_FragColor = vec4(clamp(c+vec3(correction),0.0,1.0),1.0);
        }
        """;
    public static final String FINISH = """
        precision highp float;
        varying vec2 vUv;
        uniform sampler2D uImage;
        uniform vec2 uPixel;
        uniform float uContrast;
        uniform float uLocal;
        uniform float uGamma;
        uniform float uBrightness;
        float lum(vec3 c) { return dot(c,vec3(0.2126,0.7152,0.0722)); }
        void main() {
            vec3 c=texture2D(uImage,vUv).rgb;
            float l=lum(c);
            vec2 step=uPixel*2.0;
            float surround = 0.25*(
                lum(texture2D(uImage,clamp(vUv+vec2(step.x,0.0),0.0,1.0)).rgb)
              + lum(texture2D(uImage,clamp(vUv-vec2(step.x,0.0),0.0,1.0)).rgb)
              + lum(texture2D(uImage,clamp(vUv+vec2(0.0,step.y),0.0,1.0)).rgb)
              + lum(texture2D(uImage,clamp(vUv-vec2(0.0,step.y),0.0,1.0)).rgb));
            float v=clamp((l-0.5)*uContrast+0.5,0.0,1.0);
            v=clamp(v + clamp((l-surround)*uLocal,-0.25,0.25)+uBrightness,0.0,1.0);
            v=pow(max(v,0.00001),1.0/max(uGamma,0.2));
            /* Preserve source hue: apply corrections to luma, not RGB channels. */
            gl_FragColor=vec4(clamp(c+vec3(v-l),0.0,1.0),1.0);
        }
        """;
}