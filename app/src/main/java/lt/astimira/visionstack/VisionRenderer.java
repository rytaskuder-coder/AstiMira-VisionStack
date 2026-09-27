package lt.astimira.visionstack;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class VisionRenderer implements GLSurfaceView.Renderer {
    public interface SurfaceReady { void onReady(SurfaceTexture texture); }
    public static final class Params {
        public volatile float gainA = .4f, gainB = .4f, radius = 1.6f;
        public volatile float axis = 90f, contrast = 1.05f, local = .3f;
        public volatile float gamma = 1f, brightness = 0f, zoom = 1f;
    }
    public final Params params = new Params();
    private final GLSurfaceView view;
    private final SurfaceReady ready;
    private final FloatBuffer quad;
    private final AtomicBoolean pending = new AtomicBoolean(false);
    private final float[] texMatrix = new float[16];
    private SurfaceTexture surfaceTexture;
    private int oes, programCam, programDir, programFinish;
    private final int[] fbo = new int[2], tex = new int[2];
    private int viewW = 1, viewH = 1, workW = 1, workH = 1;
    private volatile int turns = 1, cameraW = 1280, cameraH = 720;
    private boolean prepared;

    public VisionRenderer(GLSurfaceView glView, SurfaceReady cb) {
        view = glView; ready = cb;
        float[] data = {-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f};
        quad = ByteBuffer.allocateDirect(data.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(data).position(0);
        Matrix.setIdentityM(texMatrix,0);
    }
    public SurfaceTexture getSurfaceTexture() { return surfaceTexture; }
    public void setCameraSize(int w, int h) { cameraW=w; cameraH=h; view.requestRender(); }
    public void turnCamera() { turns=(turns+1)%4; view.requestRender(); }
    public void redraw() { view.requestRender(); }

    @Override public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        prepared=false;
        programCam = program(Shaders.VERT, Shaders.CAMERA);
        programDir = program(Shaders.VERT, Shaders.DIRECTIONAL);
        programFinish = program(Shaders.VERT, Shaders.FINISH);
        int[] ids = new int[1];
        GLES20.glGenTextures(1,ids,0); oes=ids[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,oes);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        surfaceTexture = new SurfaceTexture(oes);
        surfaceTexture.setOnFrameAvailableListener(texture -> {
            pending.set(true);
            view.requestRender();
        },new Handler(Looper.getMainLooper()));
        GLES20.glClearColor(0f,0f,0f,1f);
        ready.onReady(surfaceTexture);
    }
    @Override public void onSurfaceChanged(GL10 unused, int w, int h) {
        viewW=w; viewH=h;
        float scale=Math.min(1f, Math.min(720f/w, 1280f/h));
        workW=Math.max(1,Math.round(w*scale)); workH=Math.max(1,Math.round(h*scale));
        if (prepared) {
            GLES20.glDeleteFramebuffers(2,fbo,0);
            GLES20.glDeleteTextures(2,tex,0);
        }
        GLES20.glGenTextures(2,tex,0);
        GLES20.glGenFramebuffers(2,fbo,0);
        for(int i=0;i<2;i++) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,tex[i]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,workW,workH,0,
                GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,fbo[i]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,tex[i],0);
            if(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)!=GLES20.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("AstiMira framebuffer unavailable");
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);
        prepared=true;
    }
    @Override public void onDrawFrame(GL10 unused) {
        if(!prepared) return;
        if(surfaceTexture!=null && pending.getAndSet(false)) {
            try {
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(texMatrix);
            } catch(RuntimeException e) {
                Log.e("AstiMira","Camera frame update",e);
                return;
            }
        }
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,fbo[0]);
        GLES20.glViewport(0,0,workW,workH);
        GLES20.glUseProgram(programCam);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,oes);
        uni1(programCam,"uImage",0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(programCam,"uMatrix"),1,false,texMatrix,0);
        uni1f(programCam,"uTurns",(float)turns);
        float aspect=(float)viewW/viewH;
        float camAspect=(turns%2==0 ? (float)cameraW/cameraH : (float)cameraH/cameraW);
        float factor=aspect/camAspect;
        float zoom=Math.max(1f,params.zoom);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(programCam,"uCrop"),
            Math.min(1f,factor)/zoom,Math.min(1f,1f/factor)/zoom);
        drawQuad();
        directional(tex[0],fbo[1],params.axis,params.gainA);
        directional(tex[1],fbo[0],params.axis+90f,params.gainB);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);
        GLES20.glViewport(0,0,viewW,viewH);
        GLES20.glUseProgram(programFinish);
        bind2D(tex[0],programFinish);
        uni2(programFinish,"uPixel",1f/workW,1f/workH);
        uni1f(programFinish,"uContrast",params.contrast);
        uni1f(programFinish,"uLocal",params.local);
        uni1f(programFinish,"uGamma",params.gamma);
        uni1f(programFinish,"uBrightness",params.brightness);
        drawQuad();
    }
    private void directional(int input, int target, float degrees, float gain) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,target);
        GLES20.glViewport(0,0,workW,workH);
        GLES20.glUseProgram(programDir);
        bind2D(input,programDir);
        double angle=Math.toRadians(degrees);
        float r=params.radius;
        uni2(programDir,"uStep",(float)Math.cos(angle)*r/workW,
            (float)Math.sin(angle)*r/workH);
        uni1f(programDir,"uGain",gain);
        drawQuad();
    }
    private static void bind2D(int id,int prog) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,id);
        uni1(prog,"uImage",0);
    }
    private void drawQuad() {
        quad.position(0);
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glVertexAttribPointer(0,2,GLES20.GL_FLOAT,false,16,quad);
        quad.position(2);
        GLES20.glEnableVertexAttribArray(1);
        GLES20.glVertexAttribPointer(1,2,GLES20.GL_FLOAT,false,16,quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
    }
    private static int shader(int kind,String source) {
        int id=GLES20.glCreateShader(kind);
        GLES20.glShaderSource(id,source);
        GLES20.glCompileShader(id);
        int[] ok=new int[1];
        GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0) throw new IllegalStateException("GL shader: "+GLES20.glGetShaderInfoLog(id));
        return id;
    }
    private static int program(String vert,String frag) {
        int v=shader(GLES20.GL_VERTEX_SHADER,vert),f=shader(GLES20.GL_FRAGMENT_SHADER,frag);
        int p=GLES20.glCreateProgram();
        GLES20.glAttachShader(p,v);GLES20.glAttachShader(p,f);
        GLES20.glBindAttribLocation(p,0,"aPos");GLES20.glBindAttribLocation(p,1,"aUv");
        GLES20.glLinkProgram(p);
        int[] ok=new int[1];GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);
        if(ok[0]==0)throw new IllegalStateException("GL program: "+GLES20.glGetProgramInfoLog(p));
        GLES20.glDeleteShader(v);GLES20.glDeleteShader(f);
        return p;
    }
    private static void uni1(int p,String name,int value) {
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p,name),value);
    }
    private static void uni1f(int p,String name,float value) {
        GLES20.glUniform1f(GLES20.glGetUniformLocation(p,name),value);
    }
    private static void uni2(int p,String name,float x,float y) {
        GLES20.glUniform2f(GLES20.glGetUniformLocation(p,name),x,y);
    }
}