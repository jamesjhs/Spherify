package com.spherify.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.Environment;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class GLProjectionView extends GLSurfaceView {
    enum Mode {
        SPHERE,
        TINY_WORLD
    }

    private static final int EXPORT_SIZE = 1600;
    private static final int THUMBNAIL_SIZE = 320;

    private final ProjectionRenderer renderer;
    private final ScaleGestureDetector scaleDetector;
    private Bitmap panorama;
    private int[] panoramaPixels = new int[0];
    private int panoramaWidth;
    private int panoramaHeight;
    private Mode mode = Mode.SPHERE;
    private boolean inverted;
    private float yaw;
    private float pitch;
    private float roll;
    private float zoom = 1f;
    private float lastX;
    private float lastY;

    public GLProjectionView(Context context) {
        this(context, null);
    }

    public GLProjectionView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        renderer = new ProjectionRenderer();
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        setFocusable(true);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                zoom = clamp(zoom * detector.getScaleFactor(), 0.45f, 5f);
                pushStateToRenderer();
                return true;
            }
        });
    }

    public void setPanorama(Bitmap panorama) {
        this.panorama = panorama;
        panoramaWidth = panorama.getWidth();
        panoramaHeight = panorama.getHeight();
        panoramaPixels = new int[panoramaWidth * panoramaHeight];
        panorama.getPixels(panoramaPixels, 0, panoramaWidth, 0, 0, panoramaWidth, panoramaHeight);
        queueEvent(() -> renderer.setPanorama(panorama));
        requestRender();
    }

    public Mode getMode() {
        return mode;
    }

    public boolean isInverted() {
        return inverted;
    }

    public String getStatusText() {
        String modeName = mode == Mode.SPHERE ? "PhotoSphere" : "Tiny World";
        return String.format(Locale.US, "%s  |  GPU preview  |  zoom %.2fx", modeName, zoom);
    }

    public void toggleMode() {
        mode = mode == Mode.SPHERE ? Mode.TINY_WORLD : Mode.SPHERE;
        roll = 0f;
        pushStateToRenderer();
    }

    public void toggleInverted() {
        inverted = !inverted;
        pushStateToRenderer();
    }

    public void resetView() {
        yaw = 0f;
        pitch = 0f;
        roll = 0f;
        zoom = 1f;
        inverted = false;
        pushStateToRenderer();
    }

    public ProjectionExport exportProjection() throws IOException {
        Bitmap image = renderProjectionOnCpu(EXPORT_SIZE, EXPORT_SIZE);
        Bitmap thumbnail = Bitmap.createScaledBitmap(image, THUMBNAIL_SIZE, THUMBNAIL_SIZE, true);

        File directory = new File(
                getContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "Spherify");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("could not create export directory");
        }

        String stamp = String.valueOf(System.currentTimeMillis());
        String prefix = mode == Mode.SPHERE ? "photosphere" : "tinyworld";
        File imageFile = new File(directory, prefix + "-" + stamp + ".png");
        File thumbnailFile = new File(directory, prefix + "-" + stamp + "-thumb.jpg");

        writeBitmap(image, Bitmap.CompressFormat.PNG, 100, imageFile);
        writeBitmap(thumbnail, Bitmap.CompressFormat.JPEG, 86, thumbnailFile);

        image.recycle();
        thumbnail.recycle();
        return new ProjectionExport(imageFile, thumbnailFile);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        if (event.getPointerCount() > 1) {
            return true;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - lastX;
                float dy = event.getY() - lastY;
                lastX = event.getX();
                lastY = event.getY();

                yaw -= dx / Math.max(1f, getWidth()) * 360f / zoom;
                if (mode == Mode.SPHERE) {
                    pitch = clamp(pitch + dy / Math.max(1f, getHeight()) * 120f / zoom, -85f, 85f);
                } else {
                    roll += dx / Math.max(1f, getWidth()) * 240f;
                    pitch = clamp(pitch + dy / Math.max(1f, getHeight()) * 90f / zoom, -85f, 85f);
                }
                pushStateToRenderer();
                return true;
            default:
                return true;
        }
    }

    private void pushStateToRenderer() {
        Mode currentMode = mode;
        boolean currentInverted = inverted;
        float currentYaw = yaw;
        float currentPitch = pitch;
        float currentRoll = roll;
        float currentZoom = zoom;
        queueEvent(() -> renderer.setState(
                currentMode,
                currentInverted,
                currentYaw,
                currentPitch,
                currentRoll,
                currentZoom));
        requestRender();
    }

    private Bitmap renderProjectionOnCpu(int width, int height) {
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width * height];

        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double rollRad = Math.toRadians(roll);
        double cosRoll = Math.cos(rollRad);
        double sinRoll = Math.sin(rollRad);
        double aspect = width / (double) height;

        for (int y = 0; y < height; y++) {
            double ny = (2.0 * y / Math.max(1, height - 1)) - 1.0;
            for (int x = 0; x < width; x++) {
                double nx = ((2.0 * x / Math.max(1, width - 1)) - 1.0) * aspect;
                Sample sample = mode == Mode.SPHERE
                        ? sampleSphere(nx, ny, yawRad, pitchRad)
                        : sampleTinyWorld(nx, ny, yawRad, pitchRad, cosRoll, sinRoll);
                pixels[y * width + x] = samplePanorama(sample.u, sample.v);
            }
        }

        output.setPixels(pixels, 0, width, 0, 0, width, height);
        return output;
    }

    private Sample sampleSphere(double nx, double ny, double yawRad, double pitchRad) {
        double fov = Math.toRadians(92.0 / zoom);
        double x = nx * Math.tan(fov / 2.0);
        double y = -ny * Math.tan(fov / 2.0);
        double z = 1.0;

        double length = Math.sqrt(x * x + y * y + z * z);
        x /= length;
        y /= length;
        z /= length;

        double cy = Math.cos(yawRad);
        double sy = Math.sin(yawRad);
        double cp = Math.cos(pitchRad);
        double sp = Math.sin(pitchRad);

        double x1 = cy * x + sy * z;
        double z1 = -sy * x + cy * z;
        double y1 = cp * y - sp * z1;
        double z2 = sp * y + cp * z1;

        return directionToSample(x1, y1, z2);
    }

    private Sample sampleTinyWorld(
            double nx,
            double ny,
            double yawRad,
            double pitchRad,
            double cosRoll,
            double sinRoll) {
        double rx = (nx * cosRoll - ny * sinRoll) / zoom;
        double ry = (nx * sinRoll + ny * cosRoll) / zoom;
        double radius = Math.sqrt(rx * rx + ry * ry);
        double angle = Math.atan2(ry, rx);
        double polar = inverted
                ? Math.PI - 2.0 * Math.atan(radius)
                : 2.0 * Math.atan(radius);

        double sx = Math.sin(polar) * Math.cos(angle);
        double sy = inverted ? -Math.cos(polar) : Math.cos(polar);
        double sz = Math.sin(polar) * Math.sin(angle);

        double cp = Math.cos(pitchRad);
        double sp = Math.sin(pitchRad);
        double y1 = cp * sy - sp * sz;
        double z1 = sp * sy + cp * sz;

        double cy = Math.cos(yawRad);
        double syaw = Math.sin(yawRad);
        double x2 = cy * sx + syaw * z1;
        double z2 = -syaw * sx + cy * z1;

        return directionToSample(x2, y1, z2);
    }

    private Sample directionToSample(double x, double y, double z) {
        double longitude = Math.atan2(x, z);
        double latitude = Math.asin(clamp(y, -1.0, 1.0));
        double u = (longitude / (2.0 * Math.PI)) + 0.5;
        double v = 0.5 - (latitude / Math.PI);
        return new Sample(u, v);
    }

    private int samplePanorama(double u, double v) {
        int sourceX = wrap((int) (u * panoramaWidth), panoramaWidth);
        int sourceY = clamp((int) (v * panoramaHeight), 0, panoramaHeight - 1);
        return panoramaPixels[sourceY * panoramaWidth + sourceX];
    }

    private static void writeBitmap(
            Bitmap bitmap,
            Bitmap.CompressFormat format,
            int quality,
            File destination) throws IOException {
        try (FileOutputStream output = new FileOutputStream(destination)) {
            if (!bitmap.compress(format, quality, output)) {
                throw new IOException("bitmap compression failed");
            }
        }
    }

    private static int wrap(int value, int maximum) {
        int wrapped = value % maximum;
        return wrapped < 0 ? wrapped + maximum : wrapped;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class Sample {
        final double u;
        final double v;

        Sample(double u, double v) {
            this.u = u;
            this.v = v;
        }
    }

    private static final class ProjectionRenderer implements GLSurfaceView.Renderer {
        private static final String VERTEX_SHADER =
                "attribute vec2 aPosition;\n" +
                "varying vec2 vPosition;\n" +
                "void main() {\n" +
                "  vPosition = aPosition;\n" +
                "  gl_Position = vec4(aPosition, 0.0, 1.0);\n" +
                "}\n";

        private static final String FRAGMENT_SHADER =
                "precision highp float;\n" +
                "varying vec2 vPosition;\n" +
                "uniform sampler2D uTexture;\n" +
                "uniform int uMode;\n" +
                "uniform int uInverted;\n" +
                "uniform float uYaw;\n" +
                "uniform float uPitch;\n" +
                "uniform float uRoll;\n" +
                "uniform float uZoom;\n" +
                "uniform float uAspect;\n" +
                "const float PI = 3.14159265358979323846;\n" +
                "vec3 rotateYawPitch(vec3 d) {\n" +
                "  float cy = cos(uYaw);\n" +
                "  float sy = sin(uYaw);\n" +
                "  float cp = cos(uPitch);\n" +
                "  float sp = sin(uPitch);\n" +
                "  float x1 = cy * d.x + sy * d.z;\n" +
                "  float z1 = -sy * d.x + cy * d.z;\n" +
                "  float y1 = cp * d.y - sp * z1;\n" +
                "  float z2 = sp * d.y + cp * z1;\n" +
                "  return vec3(x1, y1, z2);\n" +
                "}\n" +
                "vec2 directionToUv(vec3 d) {\n" +
                "  float longitude = atan(d.x, d.z);\n" +
                "  float latitude = asin(clamp(d.y, -1.0, 1.0));\n" +
                "  float u = fract(longitude / (2.0 * PI) + 0.5);\n" +
                "  float v = clamp(0.5 - latitude / PI, 0.0, 1.0);\n" +
                "  return vec2(u, v);\n" +
                "}\n" +
                "vec3 sphereDirection(vec2 p) {\n" +
                "  float fov = radians(92.0 / uZoom);\n" +
                "  float spread = tan(fov * 0.5);\n" +
                "  return normalize(vec3(p.x * spread, -p.y * spread, 1.0));\n" +
                "}\n" +
                "vec3 tinyWorldDirection(vec2 p) {\n" +
                "  float cr = cos(uRoll);\n" +
                "  float sr = sin(uRoll);\n" +
                "  vec2 r = vec2(p.x * cr - p.y * sr, p.x * sr + p.y * cr) / uZoom;\n" +
                "  float radius = length(r);\n" +
                "  float angle = atan(r.y, r.x);\n" +
                "  float polar = uInverted == 1 ? PI - 2.0 * atan(radius) : 2.0 * atan(radius);\n" +
                "  vec3 d = vec3(sin(polar) * cos(angle), cos(polar), sin(polar) * sin(angle));\n" +
                "  if (uInverted == 1) {\n" +
                "    d.y = -d.y;\n" +
                "  }\n" +
                "  return d;\n" +
                "}\n" +
                "void main() {\n" +
                "  vec2 p = vec2(vPosition.x * uAspect, vPosition.y);\n" +
                "  vec3 d = uMode == 0 ? sphereDirection(p) : tinyWorldDirection(p);\n" +
                "  d = rotateYawPitch(d);\n" +
                "  gl_FragColor = texture2D(uTexture, directionToUv(d));\n" +
                "}\n";

        private final FloatBuffer vertices = ByteBuffer
                .allocateDirect(8 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(new float[]{-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f});

        private Bitmap pendingPanorama;
        private int textureId;
        private int program;
        private int positionHandle;
        private int textureHandle;
        private int modeHandle;
        private int invertedHandle;
        private int yawHandle;
        private int pitchHandle;
        private int rollHandle;
        private int zoomHandle;
        private int aspectHandle;
        private int viewportWidth = 1;
        private int viewportHeight = 1;
        private Mode mode = Mode.SPHERE;
        private boolean inverted;
        private float yaw;
        private float pitch;
        private float roll;
        private float zoom = 1f;

        ProjectionRenderer() {
            vertices.position(0);
        }

        void setPanorama(Bitmap panorama) {
            pendingPanorama = panorama;
            if (program != 0) {
                uploadPendingPanorama();
            }
        }

        void setState(Mode mode, boolean inverted, float yaw, float pitch, float roll, float zoom) {
            this.mode = mode;
            this.inverted = inverted;
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
            this.zoom = zoom;
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0.02f, 0.04f, 0.06f, 1f);
            program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
            textureHandle = GLES20.glGetUniformLocation(program, "uTexture");
            modeHandle = GLES20.glGetUniformLocation(program, "uMode");
            invertedHandle = GLES20.glGetUniformLocation(program, "uInverted");
            yawHandle = GLES20.glGetUniformLocation(program, "uYaw");
            pitchHandle = GLES20.glGetUniformLocation(program, "uPitch");
            rollHandle = GLES20.glGetUniformLocation(program, "uRoll");
            zoomHandle = GLES20.glGetUniformLocation(program, "uZoom");
            aspectHandle = GLES20.glGetUniformLocation(program, "uAspect");
            uploadPendingPanorama();
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            viewportWidth = Math.max(1, width);
            viewportHeight = Math.max(1, height);
            GLES20.glViewport(0, 0, viewportWidth, viewportHeight);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (textureId == 0) {
                return;
            }

            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            GLES20.glUniform1i(textureHandle, 0);
            GLES20.glUniform1i(modeHandle, mode == Mode.SPHERE ? 0 : 1);
            GLES20.glUniform1i(invertedHandle, inverted ? 1 : 0);
            GLES20.glUniform1f(yawHandle, (float) Math.toRadians(yaw));
            GLES20.glUniform1f(pitchHandle, (float) Math.toRadians(pitch));
            GLES20.glUniform1f(rollHandle, (float) Math.toRadians(roll));
            GLES20.glUniform1f(zoomHandle, zoom);
            GLES20.glUniform1f(aspectHandle, viewportWidth / (float) viewportHeight);

            GLES20.glEnableVertexAttribArray(positionHandle);
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertices);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionHandle);
        }

        private void uploadPendingPanorama() {
            if (pendingPanorama == null) {
                return;
            }
            if (textureId == 0) {
                int[] textures = new int[1];
                GLES20.glGenTextures(1, textures, 0);
                textureId = textures[0];
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, pendingPanorama, 0);
            pendingPanorama = null;
        }

        private static int buildProgram(String vertexSource, String fragmentSource) {
            int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
            int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertexShader);
            GLES20.glAttachShader(program, fragmentShader);
            GLES20.glLinkProgram(program);

            int[] status = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) {
                String log = GLES20.glGetProgramInfoLog(program);
                GLES20.glDeleteProgram(program);
                throw new IllegalStateException("GL program link failed: " + log);
            }
            return program;
        }

        private static int compileShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);

            int[] status = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                String log = GLES20.glGetShaderInfoLog(shader);
                GLES20.glDeleteShader(shader);
                throw new IllegalStateException("GL shader compile failed: " + log);
            }
            return shader;
        }
    }
}
