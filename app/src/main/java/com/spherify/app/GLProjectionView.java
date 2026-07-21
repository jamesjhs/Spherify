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
        TINY_PLANET
    }

    private static final int EXPORT_SIZE = 1600;
    private static final int THUMBNAIL_SIZE = 320;
    private static final float PHOTOSPHERE_BASE_ROLL_DEGREES = 180f;
    private static final float BASE_FIELD_OF_VIEW_DEGREES = 92f;

    private final ProjectionRenderer renderer;
    private final ScaleGestureDetector scaleDetector;
    private Bitmap panorama;
    private int[] panoramaPixels = new int[0];
    private int panoramaWidth;
    private int panoramaHeight;
    private boolean sourceTinyPlanet;
    private Mode mode = Mode.SPHERE;
    private float centerYaw;
    private float centerPitch;
    private float centerRoll;
    private float yaw;
    private float pitch;
    private float roll;
    private float horizonOffset;
    private float zoom = 1f;
    private float lastX;
    private float lastY;
    private float lastPointerAngle;
    private float lastPointerFocusX;
    private float lastPointerFocusY;
    private boolean rotatingGesture;

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
                zoom = clamp(zoom * detector.getScaleFactor(), getMinimumZoom(), 5f);
                pushStateToRenderer();
                return true;
            }
        });
    }

    public void setPanorama(Bitmap panorama, String sourceProjection) {
        this.panorama = panorama;
        panoramaWidth = panorama.getWidth();
        panoramaHeight = panorama.getHeight();
        sourceTinyPlanet = "tinyplanet".equals(sourceProjection) || isSquareish(panoramaWidth, panoramaHeight);
        panoramaPixels = new int[panoramaWidth * panoramaHeight];
        panorama.getPixels(panoramaPixels, 0, panoramaWidth, 0, 0, panoramaWidth, panoramaHeight);
        centerYaw = 0f;
        centerPitch = 0f;
        centerRoll = 0f;
        yaw = 0f;
        pitch = 0f;
        roll = 0f;
        horizonOffset = 0f;
        boolean currentSourceTinyPlanet = sourceTinyPlanet;
        queueEvent(() -> renderer.setPanorama(panorama, currentSourceTinyPlanet));
        pushStateToRenderer();
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        zoom = Math.max(zoom, getMinimumZoom());
        roll = 0f;
        pushStateToRenderer();
    }

    public String getStatusText() {
        String modeName = mode == Mode.SPHERE ? "Photo Sphere" : "Tiny Planet";
        return String.format(Locale.US, "%s  |  GPU preview  |  FOV %.0f  |  horizon %.0f",
                modeName,
                getFieldOfViewDegrees(),
                getEyeElevationDegrees());
    }

    public void toggleMode() {
        mode = mode == Mode.SPHERE ? Mode.TINY_PLANET : Mode.SPHERE;
        zoom = Math.max(zoom, getMinimumZoom());
        roll = 0f;
        pushStateToRenderer();
    }

    public void recentre() {
        centerYaw = normalizeDegrees(centerYaw + yaw);
        centerPitch = mode == Mode.SPHERE
                ? clamp(centerPitch + pitch, -89f, 89f)
                : normalizeDegrees(centerPitch + pitch);
        centerRoll = normalizeDegrees(centerRoll + roll);
        yaw = 0f;
        pitch = 0f;
        roll = 0f;
        pushStateToRenderer();
    }

    public void resetView() {
        yaw = 0f;
        pitch = 0f;
        roll = 0f;
        horizonOffset = 0f;
        zoom = 1f;
        pushStateToRenderer();
    }

    public float getFieldOfViewDegrees() {
        return BASE_FIELD_OF_VIEW_DEGREES / zoom;
    }

    public void setFieldOfViewDegrees(float degrees) {
        zoom = clamp(BASE_FIELD_OF_VIEW_DEGREES / clamp(degrees, 30f, 180f), getMinimumZoom(), 5f);
        pushStateToRenderer();
    }

    public float getImageRotationDegrees() {
        return roll;
    }

    public void setImageRotationDegrees(float degrees) {
        roll = normalizeDegrees(degrees);
        pushStateToRenderer();
    }

    public float getEyeElevationDegrees() {
        return horizonOffset;
    }

    public void setEyeElevationDegrees(float degrees) {
        horizonOffset = clamp(degrees, -75f, 75f);
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
        String prefix = mode == Mode.SPHERE ? "photosphere" : "tinyplanet";
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

        if (event.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
            int liftedPointer = event.getActionIndex();
            int remainingPointer = liftedPointer == 0 ? 1 : 0;
            if (remainingPointer < event.getPointerCount()) {
                lastX = event.getX(remainingPointer);
                lastY = event.getY(remainingPointer);
            }
            rotatingGesture = false;
            return true;
        }

        if (event.getPointerCount() > 1) {
            handleTwoFingerGesture(event);
            return true;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                rotatingGesture = false;
                lastX = event.getX();
                lastY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - lastX;
                float dy = event.getY() - lastY;
                lastX = event.getX();
                lastY = event.getY();

                applyDragDelta(dx, dy);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                rotatingGesture = false;
                return true;
            default:
                return true;
        }
    }

    private void handleTwoFingerGesture(MotionEvent event) {
        float pointerDx = event.getX(1) - event.getX(0);
        float pointerDy = event.getY(1) - event.getY(0);
        float angle = (float) Math.toDegrees(Math.atan2(pointerDy, pointerDx));
        float focusX = (event.getX(0) + event.getX(1)) * 0.5f;
        float focusY = (event.getY(0) + event.getY(1)) * 0.5f;
        if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN || !rotatingGesture) {
            lastPointerAngle = angle;
            lastPointerFocusX = focusX;
            lastPointerFocusY = focusY;
            rotatingGesture = true;
            return;
        }

        float focusDx = focusX - lastPointerFocusX;
        float focusDy = focusY - lastPointerFocusY;
        roll = normalizeDegrees(roll + normalizeDegrees(angle - lastPointerAngle));
        lastPointerAngle = angle;
        lastPointerFocusX = focusX;
        lastPointerFocusY = focusY;
        applyDragDelta(focusDx, focusDy);
    }

    private void applyDragDelta(float dx, float dy) {
        float verticalDragReference = Math.max(1f, Math.min(getWidth(), getHeight()));
        if (mode == Mode.SPHERE) {
            yaw = normalizeDegrees(yaw + dx / Math.max(1f, getWidth()) * 180f / zoom);
            pitch = clamp(pitch - dy / verticalDragReference * 120f / zoom, -89f, 89f);
        } else {
            yaw = normalizeDegrees(yaw - dx / Math.max(1f, getWidth()) * 180f / zoom);
            roll = normalizeDegrees(roll + dx / Math.max(1f, getWidth()) * 240f);
            pitch = normalizeDegrees(pitch + dy / verticalDragReference * 180f / zoom);
        }
        pushStateToRenderer();
    }

    private void pushStateToRenderer() {
        Mode currentMode = mode;
        float currentYaw = getEffectiveYaw();
        float currentPitch = getEffectivePitch();
        float currentRoll = getEffectiveRoll();
        float currentHorizonOffset = horizonOffset;
        float currentZoom = zoom;
        queueEvent(() -> renderer.setState(
                currentMode,
                currentYaw,
                currentPitch,
                currentRoll,
                currentHorizonOffset,
                currentZoom));
        requestRender();
    }

    private Bitmap renderProjectionOnCpu(int width, int height) {
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width * height];

        double yawRad = Math.toRadians(getEffectiveYaw());
        double pitchRad = Math.toRadians(getEffectivePitch());
        double rollRad = Math.toRadians(getEffectiveRoll());
        double horizonOffsetRad = Math.toRadians(horizonOffset);
        double cosRoll = Math.cos(rollRad);
        double sinRoll = Math.sin(rollRad);
        double aspect = width / (double) height;

        for (int y = 0; y < height; y++) {
            double rowPosition = 2.0 * y / Math.max(1, height - 1);
            double ny = mode == Mode.TINY_PLANET ? 1.0 - rowPosition : rowPosition - 1.0;
            for (int x = 0; x < width; x++) {
                double nx = ((2.0 * x / Math.max(1, width - 1)) - 1.0) * aspect;
                Sample sample = mode == Mode.SPHERE
                        ? sampleSphere(nx, ny, yawRad, pitchRad)
                        : sampleTinyPlanet(nx, ny, yawRad, pitchRad, cosRoll, sinRoll);
                pixels[y * width + x] = sourceTinyPlanet
                        ? sampleTinyPlanetSource(sample.x, sample.y, sample.z, horizonOffsetRad)
                        : samplePanorama(sample.u, sample.v, horizonOffsetRad);
            }
        }

        output.setPixels(pixels, 0, width, 0, 0, width, height);
        return output;
    }

    private Sample sampleSphere(double nx, double ny, double yawRad, double pitchRad) {
        double rollRad = Math.toRadians(getEffectiveRoll());
        double cosRoll = Math.cos(rollRad);
        double sinRoll = Math.sin(rollRad);
        double rx = nx * cosRoll - ny * sinRoll;
        double ry = nx * sinRoll + ny * cosRoll;
        double fov = Math.toRadians(BASE_FIELD_OF_VIEW_DEGREES / zoom);
        double spread = Math.tan(fov * 0.5);

        double forwardX = Math.sin(yawRad) * Math.cos(pitchRad);
        double forwardY = Math.sin(pitchRad);
        double forwardZ = Math.cos(yawRad) * Math.cos(pitchRad);
        double rightX = Math.cos(yawRad);
        double rightY = 0.0;
        double rightZ = -Math.sin(yawRad);
        double upX = forwardY * rightZ - forwardZ * rightY;
        double upY = forwardZ * rightX - forwardX * rightZ;
        double upZ = forwardX * rightY - forwardY * rightX;

        double x = forwardX + rightX * rx * spread + upX * ry * spread;
        double y = forwardY + rightY * rx * spread + upY * ry * spread;
        double z = forwardZ + rightZ * rx * spread + upZ * ry * spread;
        double length = Math.sqrt(x * x + y * y + z * z);
        return directionToSample(x / length, y / length, z / length);
    }

    private Sample sampleTinyPlanet(
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
        double polar = 2.0 * Math.atan(radius);

        double sx = Math.sin(polar) * Math.cos(angle);
        double sy = Math.cos(polar);
        double sz = Math.sin(polar) * Math.sin(angle);

        double cy = Math.cos(yawRad);
        double syaw = Math.sin(yawRad);
        double x1 = cy * sx + syaw * sz;
        double z1 = -syaw * sx + cy * sz;

        double cp = Math.cos(pitchRad);
        double sp = Math.sin(pitchRad);
        double y2 = cp * sy - sp * z1;
        double z2 = sp * sy + cp * z1;

        return directionToSample(x1, y2, z2);
    }

    private Sample directionToSample(double x, double y, double z) {
        double longitude = Math.atan2(x, z);
        double latitude = Math.asin(clamp(y, -1.0, 1.0));
        double u = (longitude / (2.0 * Math.PI)) + 0.5;
        double v = 0.5 - (latitude / Math.PI);
        return new Sample(u, v, x, y, z);
    }

    private int samplePanorama(double u, double v, double horizonOffsetRad) {
        int sourceX = wrap((int) (u * panoramaWidth), panoramaWidth);
        v += horizonOffsetRad / Math.PI;
        int sourceY = clamp((int) (v * panoramaHeight), 0, panoramaHeight - 1);
        return panoramaPixels[sourceY * panoramaWidth + sourceX];
    }

    private int sampleTinyPlanetSource(double x, double y, double z, double horizonOffsetRad) {
        double polar = clamp(Math.acos(clamp(y, -1.0, 1.0)) + horizonOffsetRad, 0.0, Math.PI - 0.001);
        double radius = Math.tan(polar * 0.5);
        double angle = Math.atan2(z, x);
        double u = 0.5 + Math.cos(angle) * radius * 0.5;
        double v = 0.5 + Math.sin(angle) * radius * 0.5;
        int sourceX = clamp((int) (u * panoramaWidth), 0, panoramaWidth - 1);
        int sourceY = clamp((int) (v * panoramaHeight), 0, panoramaHeight - 1);
        return panoramaPixels[sourceY * panoramaWidth + sourceX];
    }

    private float getEffectiveYaw() {
        return normalizeDegrees(centerYaw + yaw);
    }

    private float getEffectivePitch() {
        if (mode == Mode.SPHERE) {
            return clamp(centerPitch + pitch, -89f, 89f);
        }
        return normalizeDegrees(centerPitch + pitch);
    }

    private float getEffectiveRoll() {
        float baseRoll = mode == Mode.SPHERE ? PHOTOSPHERE_BASE_ROLL_DEGREES : 0f;
        return normalizeDegrees(baseRoll + centerRoll + roll);
    }

    private float getMinimumZoom() {
        return mode == Mode.TINY_PLANET ? 0.1f : 0.45f;
    }

    private static float normalizeDegrees(float value) {
        float normalized = value % 360f;
        if (normalized > 180f) {
            normalized -= 360f;
        } else if (normalized < -180f) {
            normalized += 360f;
        }
        return normalized;
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

    private static boolean isSquareish(int width, int height) {
        int larger = Math.max(width, height);
        int smaller = Math.min(width, height);
        return larger > 0 && smaller / (float) larger > 0.9f;
    }

    private static final class Sample {
        final double u;
        final double v;
        final double x;
        final double y;
        final double z;

        Sample(double u, double v, double x, double y, double z) {
            this.u = u;
            this.v = v;
            this.x = x;
            this.y = y;
            this.z = z;
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
                "uniform float uYaw;\n" +
                "uniform float uPitch;\n" +
                "uniform float uRoll;\n" +
                "uniform float uZoom;\n" +
                "uniform float uAspect;\n" +
                "uniform float uHorizonOffset;\n" +
                "uniform int uSourceProjection;\n" +
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
                "vec2 equirectUv(vec3 d) {\n" +
                "  float longitude = atan(d.x, d.z);\n" +
                "  float latitude = asin(clamp(d.y, -1.0, 1.0));\n" +
                "  float u = fract(longitude / (2.0 * PI) + 0.5);\n" +
                "  float v = clamp(0.5 - latitude / PI + uHorizonOffset / PI, 0.0, 1.0);\n" +
                "  return vec2(u, v);\n" +
                "}\n" +
                "vec2 tinyPlanetUv(vec3 d) {\n" +
                "  float polar = clamp(acos(clamp(d.y, -1.0, 1.0)) + uHorizonOffset, 0.0, PI - 0.001);\n" +
                "  float radius = tan(polar * 0.5);\n" +
                "  float angle = atan(d.z, d.x);\n" +
                "  vec2 uv = vec2(0.5 + cos(angle) * radius * 0.5, 0.5 + sin(angle) * radius * 0.5);\n" +
                "  return clamp(uv, 0.0, 1.0);\n" +
                "}\n" +
                "vec4 sampleSource(vec3 d) {\n" +
                "  vec2 uv = uSourceProjection == 1 ? tinyPlanetUv(d) : equirectUv(d);\n" +
                "  return texture2D(uTexture, vec2(uv.x, 1.0 - uv.y));\n" +
                "}\n" +
                "vec2 rollScreen(vec2 p) {\n" +
                "  float cr = cos(uRoll);\n" +
                "  float sr = sin(uRoll);\n" +
                "  return vec2(p.x * cr - p.y * sr, p.x * sr + p.y * cr);\n" +
                "}\n" +
                "vec3 sphereDirection(vec2 p) {\n" +
                "  vec2 r = rollScreen(p);\n" +
                "  float spread = tan(radians(92.0 / uZoom) * 0.5);\n" +
                "  vec3 forward = vec3(sin(uYaw) * cos(uPitch), sin(uPitch), cos(uYaw) * cos(uPitch));\n" +
                "  vec3 right = vec3(cos(uYaw), 0.0, -sin(uYaw));\n" +
                "  vec3 up = cross(forward, right);\n" +
                "  return normalize(forward + right * r.x * spread + up * r.y * spread);\n" +
                "}\n" +
                "vec3 tinyPlanetDirection(vec2 p) {\n" +
                "  vec2 r = rollScreen(p) / uZoom;\n" +
                "  float radius = length(r);\n" +
                "  float angle = atan(r.y, r.x);\n" +
                "  float polar = 2.0 * atan(radius);\n" +
                "  vec3 d = vec3(sin(polar) * cos(angle), cos(polar), sin(polar) * sin(angle));\n" +
                "  return d;\n" +
                "}\n" +
                "void main() {\n" +
                "  vec2 p = vec2(vPosition.x * uAspect, vPosition.y);\n" +
                "  vec3 d = uMode == 0 ? sphereDirection(p) : rotateYawPitch(tinyPlanetDirection(p));\n" +
                "  gl_FragColor = sampleSource(d);\n" +
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
        private int yawHandle;
        private int pitchHandle;
        private int rollHandle;
        private int zoomHandle;
        private int aspectHandle;
        private int horizonOffsetHandle;
        private int sourceProjectionHandle;
        private int viewportWidth = 1;
        private int viewportHeight = 1;
        private boolean sourceTinyPlanet;
        private Mode mode = Mode.SPHERE;
        private float yaw;
        private float pitch;
        private float roll;
        private float horizonOffset;
        private float zoom = 1f;

        ProjectionRenderer() {
            vertices.position(0);
        }

        void setPanorama(Bitmap panorama, boolean sourceTinyPlanet) {
            pendingPanorama = panorama;
            this.sourceTinyPlanet = sourceTinyPlanet;
            if (program != 0) {
                uploadPendingPanorama();
            }
        }

        void setState(Mode mode, float yaw, float pitch, float roll, float horizonOffset, float zoom) {
            this.mode = mode;
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
            this.horizonOffset = horizonOffset;
            this.zoom = zoom;
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0.02f, 0.04f, 0.06f, 1f);
            program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
            textureHandle = GLES20.glGetUniformLocation(program, "uTexture");
            modeHandle = GLES20.glGetUniformLocation(program, "uMode");
            yawHandle = GLES20.glGetUniformLocation(program, "uYaw");
            pitchHandle = GLES20.glGetUniformLocation(program, "uPitch");
            rollHandle = GLES20.glGetUniformLocation(program, "uRoll");
            zoomHandle = GLES20.glGetUniformLocation(program, "uZoom");
            aspectHandle = GLES20.glGetUniformLocation(program, "uAspect");
            horizonOffsetHandle = GLES20.glGetUniformLocation(program, "uHorizonOffset");
            sourceProjectionHandle = GLES20.glGetUniformLocation(program, "uSourceProjection");
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
            GLES20.glUniform1f(yawHandle, (float) Math.toRadians(yaw));
            GLES20.glUniform1f(pitchHandle, (float) Math.toRadians(pitch));
            GLES20.glUniform1f(rollHandle, (float) Math.toRadians(roll));
            GLES20.glUniform1f(zoomHandle, zoom);
            GLES20.glUniform1f(aspectHandle, viewportWidth / (float) viewportHeight);
            GLES20.glUniform1f(horizonOffsetHandle, (float) Math.toRadians(horizonOffset));
            GLES20.glUniform1i(sourceProjectionHandle, sourceTinyPlanet ? 1 : 0);

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
