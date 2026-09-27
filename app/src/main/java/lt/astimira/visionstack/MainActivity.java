package lt.astimira.visionstack;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Arrays;
import java.util.Locale;

public class MainActivity extends Activity {
    private GLSurfaceView preview;
    private VisionRenderer render;
    private LinearLayout panel;
    private SharedPreferences prefs;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private Surface cameraSurface;
    private boolean opening;
    private int eye=0;
    private float sp, cyl, axis, offset, gainA, gainB, radius;
    private float contrast, local, gamma, brightness, zoom;
    private TextView prescriptionInfo;
    private static final int CAMERA_REQUEST=101;

    private String k(String name) { return (eye==0?"OD_":"OS_")+name; }
    private float get(String name,float fallback) { return prefs.getFloat(k(name),fallback); }
    private void save(String name,float value) { prefs.edit().putFloat(k(name),value).apply(); }
    private int dp(float d) { return (int)(d*getResources().getDisplayMetrics().density+.5f); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(7,12,21));
        getWindow().setNavigationBarColor(Color.rgb(7,12,21));
        prefs=getSharedPreferences("astimira_visionstack_v1",MODE_PRIVATE);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(7,12,21));
        preview=new GLSurfaceView(this);
        preview.setEGLContextClientVersion(2);
        render=new VisionRenderer(preview,texture -> runOnUiThread(() -> startCamera(texture)));
        preview.setPreserveEGLContextOnPause(true);
        preview.setRenderer(render);
        preview.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        root.addView(preview,new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,0,1f));
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setBackgroundColor(Color.rgb(16,26,40));
        panel=new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(15),dp(10),dp(15),dp(25));
        scroll.addView(panel);
        root.addView(scroll,new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,dp(340)));
        setContentView(root);
        loadEye();
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_REQUEST);
    }
    private TextView label(String message,int size) {
        TextView t=new TextView(this);
        t.setText(message);
        t.setTextSize(size);
        t.setTextColor(Color.WHITE);
        t.setPadding(0,dp(6),0,dp(5));
        return t;
    }
    private Button button(String title,Runnable task) {
        Button b=new Button(this);
        b.setAllCaps(false);
        b.setText(title);
        b.setTextSize(13);
        b.setOnClickListener(v -> task.run());
        return b;
    }
    private void buttonRow(Button a,Button b) {
        LinearLayout r=new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.addView(a,new LinearLayout.LayoutParams(0,dp(52),1));
        r.addView(b,new LinearLayout.LayoutParams(0,dp(52),1));
        panel.addView(r);
    }
    private interface Change { void onChange(float value); }
    private void slider(String name,float min,float max,float value,Change change) {
        TextView text=label("",15);
        panel.addView(text);
        SeekBar bar=new SeekBar(this);
        bar.setMax(200);
        int start=Math.max(0,Math.min(200,Math.round(200f*(value-min)/(max-min))));
        bar.setProgress(start);
        text.setText(String.format(Locale.getDefault(),"%s: %.2f",name,value));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb,int p,boolean fromUser) {
                if(!fromUser)return;
                float v=min+(max-min)*p/200f;
                text.setText(String.format(Locale.getDefault(),"%s: %.2f",name,v));
                change.onChange(v);
                apply();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        panel.addView(bar);
    }
    private void loadEye() {
        sp=get("sp",0f);cyl=get("cyl",0f);axis=get("axis",90f);
        gainA=get("gainA",Math.min(1.2f,.25f+Math.abs(sp)*.10f));
        gainB=get("gainB",Math.min(1.2f,.25f+Math.abs(sp+cyl)*.10f));
        radius=get("radius",Math.min(3f,1.25f+.15f*(Math.abs(sp)+Math.abs(cyl))));
        offset=get("offset",0f);contrast=get("contrast",1.05f);
        local=get("local",.3f);gamma=get("gamma",1f);
        brightness=get("brightness",0f);zoom=get("zoom",1f);
        buildPanel();
        apply();
    }
    private void buildPanel() {
        panel.removeAllViews();
        panel.addView(label("ASTIMIRA  |  VISIONSTACK",21));
        panel.addView(label("Экспериментальная многослойная коррекция видео. Не заменяет очки.",12));
        buttonRow(
            button("Правый глаз OD",()->{eye=0;loadEye();}),
            button("Левый глаз OS",()->{eye=1;loadEye();}));
        buttonRow(
            button("Ввести рецепт",this::recipeDialog),
            button("Повернуть видео",()->{render.turnCamera();toast("Поворот камеры изменён");}));
        prescriptionInfo=label("",14);
        panel.addView(prescriptionInfo);
        slider("Слой 1 • направление оси",0f,2f,gainA,v->{gainA=v;save("gainA",v);});
        slider("Слой 2 • поперёк оси",0f,2f,gainB,v->{gainB=v;save("gainB",v);});
        slider("Радиус направленной коррекции",.5f,5f,radius,v->{radius=v;save("radius",v);});
        slider("Тонкая настройка оси (°)",-90f,90f,offset,v->{offset=v;save("offset",v);});
        slider("Общий контраст",.6f,2.5f,contrast,v->{contrast=v;save("contrast",v);});
        slider("Локальный контраст",0f,2f,local,v->{local=v;save("local",v);});
        slider("Гамма",.5f,1.8f,gamma,v->{gamma=v;save("gamma",v);});
        slider("Осветление",-0.25f,.25f,brightness,v->{brightness=v;save("brightness",v);});
        slider("Размер лица",1f,2.5f,zoom,v->{zoom=v;save("zoom",v);});
        buttonRow(button("Сохранено ✓",()->toast("Настройки этого глаза сохраняются автоматически")),
            button("Сбросить фильтры",this::resetFilters));
        panel.addView(label("Начинай с небольшой силы двух слоёв. Если возникли ореолы или картинка стала хуже, снизь силу и радиус. Камера работает локально.",12));
    }
    private void apply() {
        if(render==null)return;
        VisionRenderer.Params p=render.params;
        p.axis=axis+offset;
        p.gainA=gainA;p.gainB=gainB;p.radius=radius;
        p.contrast=contrast;p.local=local;p.gamma=gamma;
        p.brightness=brightness;p.zoom=zoom;
        if(prescriptionInfo!=null)prescriptionInfo.setText(String.format(Locale.getDefault(),
            "%s  •  SPH %.2f  CYL %.2f  AXIS %.0f°  •  настройка %.0f°",
            eye==0?"OD":"OS",sp,cyl,axis,axis+offset));
        render.redraw();
    }
    private void resetFilters() {
        String[] names={"gainA","gainB","radius","offset","contrast","local","gamma","brightness","zoom"};
        SharedPreferences.Editor ed=prefs.edit();
        for(String name:names) ed.remove(k(name));
        ed.apply();
        loadEye();toast("Слои сброшены; рецепт сохранён");
    }
    private EditText field(String hint,float value) {
        EditText e=new EditText(this);
        e.setSingleLine(true);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER |
            android.text.InputType.TYPE_NUMBER_FLAG_SIGNED |
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setHint(hint);
        e.setText(Float.toString(value));
        return e;
    }
    private void recipeDialog() {
        LinearLayout fields=new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(18),dp(6),dp(18),0);
        TextView desc=label("Введите данные очков для выбранного глаза:",15);
        fields.addView(desc);
        EditText sph=field("SPH",sp), cylinder=field("CYL",cyl), degrees=field("AXIS 0–180",axis);
        fields.addView(sph);fields.addView(cylinder);fields.addView(degrees);
        AlertDialog dialog=new AlertDialog.Builder(this)
            .setTitle(eye==0?"Рецепт • правый глаз OD":"Рецепт • левый глаз OS")
            .setView(fields)
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Применить",null)
            .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                float a=Float.parseFloat(sph.getText().toString().replace(',','.'));
                float b=Float.parseFloat(cylinder.getText().toString().replace(',','.'));
                float c=Float.parseFloat(degrees.getText().toString().replace(',','.'));
                if(!Float.isFinite(a)||!Float.isFinite(b)||!Float.isFinite(c)||c<0||c>180 ||
                   Math.abs(a)>30||Math.abs(b)>15) {
                    toast("Проверь SPH, CYL и AXIS (0–180)");return;
                }
                sp=a;cyl=b;axis=c;
                float initialA=Math.min(1.2f,.25f+Math.abs(a)*.10f);
                float initialB=Math.min(1.2f,.25f+Math.abs(a+b)*.10f);
                prefs.edit().putFloat(k("sp"),a).putFloat(k("cyl"),b)
                    .putFloat(k("axis"),c).putFloat(k("gainA"),initialA)
                    .putFloat(k("gainB"),initialB).putFloat(k("offset"),0f)
                    .apply();
                dialog.dismiss();loadEye();
            } catch(NumberFormatException e) { toast("Заполни все три поля числом"); }
        }));
        dialog.show();
    }
    private void toast(String message) {
        Toast.makeText(this,message,Toast.LENGTH_SHORT).show();
    }
    @Override public void onRequestPermissionsResult(int req,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(req,permissions,grants);
        if(req==CAMERA_REQUEST && grants.length>0 && grants[0]==PackageManager.PERMISSION_GRANTED)
            startCamera(render.getSurfaceTexture());
        else if(req==CAMERA_REQUEST)toast("Для зеркала нужен доступ к передней камере");
    }
    private void startCamera(SurfaceTexture texture) {
        if(texture==null||camera!=null||opening||
           checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
        try {
            CameraManager manager=(CameraManager)getSystemService(Context.CAMERA_SERVICE);
            String front=null;
            for(String id:manager.getCameraIdList()) {
                Integer direction=manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if(direction!=null&&direction==CameraCharacteristics.LENS_FACING_FRONT) { front=id;break; }
            }
            if(front==null) {toast("Передняя камера не найдена");return;}
            StreamConfigurationMap map=manager.getCameraCharacteristics(front).get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if(map==null) {toast("Нет подходящего видеорежима");return;}
            Size[] sizes=map.getOutputSizes(SurfaceTexture.class);
            if(sizes==null||sizes.length==0)return;
            Size chosen=sizes[0];
            long score=Long.MAX_VALUE;
            for(Size size:sizes) {
                long s=Math.abs((long)size.getWidth()*size.getHeight()-1280L*720);
                double ratio=(double)size.getWidth()/size.getHeight();
                if(ratio<1.65||ratio>1.85)s+=10000000;
                if(s<score){score=s;chosen=size;}
            }
            texture.setDefaultBufferSize(chosen.getWidth(),chosen.getHeight());
            render.setCameraSize(chosen.getWidth(),chosen.getHeight());
            cameraSurface=new Surface(texture);
            opening=true;
            manager.openCamera(front,new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    opening=false;camera=device;
                    try {
                        device.createCaptureSession(Arrays.asList(cameraSurface),
                            new CameraCaptureSession.StateCallback() {
                                @Override public void onConfigured(CameraCaptureSession s) {
                                    if(camera!=device){s.close();return;}
                                    session=s;
                                    try {
                                        CaptureRequest.Builder request=device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                        request.addTarget(cameraSurface);
                                        request.set(CaptureRequest.CONTROL_AF_MODE,
                                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                        session.setRepeatingRequest(request.build(),null,
                                            new Handler(Looper.getMainLooper()));
                                    }catch(CameraAccessException | IllegalStateException ex) {
                                        toast("Не удалось запустить видео");
                                    }
                                }
                                @Override public void onConfigureFailed(CameraCaptureSession s) {
                                    toast("Не удалось настроить камеру");
                                }
                            },new Handler(Looper.getMainLooper()));
                    }catch(CameraAccessException | IllegalStateException ex) {
                        toast("Ошибка соединения с камерой");
                    }
                }
                @Override public void onDisconnected(CameraDevice device) {
                    device.close();camera=null;opening=false;
                }
                @Override public void onError(CameraDevice device,int error) {
                    device.close();camera=null;opening=false;
                    toast("Камера недоступна: "+error);
                }
            },new Handler(Looper.getMainLooper()));
        } catch(CameraAccessException | SecurityException | IllegalArgumentException ex) {
            opening=false;toast("Доступ к камере невозможен");
        }
    }
    private void closeCamera() {
        if(session!=null){session.close();session=null;}
        if(camera!=null){camera.close();camera=null;}
        if(cameraSurface!=null){cameraSurface.release();cameraSurface=null;}
        opening=false;
    }
    @Override protected void onResume() {
        super.onResume();
        if(preview!=null)preview.onResume();
        if(render!=null)startCamera(render.getSurfaceTexture());
    }
    @Override protected void onPause() {
        closeCamera();
        if(preview!=null)preview.onPause();
        super.onPause();
    }
}