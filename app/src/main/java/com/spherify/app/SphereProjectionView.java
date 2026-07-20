package com.spherify.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Environment;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

public class SphereProjectionView extends View {
    enum Mode {
        SPHERE,
        TINY_WORLD
    }

    private static final int EXPORT_SIZE = 1600;
    private static final int THUMBNAIL_SIZE = 320;

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final ScaleGestureDetector scaleDetector;
    private Bitmap panorama;
    private Bitmap preview;
    private Mode mode = Mode.SPHERE;
    private boolean inverted;
    private float yaw;
    private float pitch;
    private float roll;
    private float zoom = 1f;
    private float lastX;
    private float lastY;
    private boolean dirty = true;

    public SphereProjectionView(Context context) {
        this(context, null);
    }

    public SphereProjectionView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                zoom = clamp(zoom * detector.getScaleFactor(), 0.45f, 5f);
                markDirty();
                return true;
            }
        });
    }

    public void setPanorama(Bitmap panorama) {
        this.panorama = panorama;
        markDirty();
    }

    public Mode getMode() {
        return mode;
    }

    public boolean isInverted() {
        return inverted;
    }

    public String getStatusText() {
        String modeName = mode == Mode.SPHERE ? "PhotoSphere" : "Tiny World";
        return String.format(Locale.US, "%s  |  zoom %.2fx  |  drag to rotate, pinch to zoom", modeName, zoom);
    }

    public void toggleMode() {
        mode = mode == Mode.SPHERE ? Mode.TINY_WORLD : Mode.SPHERE;
        roll = 0f;
        markDirty();
    }

    public void toggleInverted() {
        inverted = !inverted;
        markDirty();
    }

    public void resetView() {
        yaw = 0f;
        pitch = 0f;
        roll = 0f;
        zoom = 1f;
        inverted = false;
        markDirty();
    }

    public ProjectionExport exportProjection() throws IOException {
        Bitmap image = renderProjection(EXPORT_SIZE, EXPORT_SIZE);
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
                markDirty();
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (panorama == null || getWidth() <= 0 || getHeight() <= 0) {
            canvas.drawColor(Color.BLACK);
            return;
        }

        if (dirty || preview == null || preview.getWidth() != getWidth() || preview.getHeight() != getHeight()) {
            if (preview != null) {
                preview.recycle();
            }
            preview = renderProjection(getWidth(), getHeight());
            dirty = false;
        }
        canvas.drawBitmap(preview, 0f, 0f, paint);
    }

    private Bitmap renderProjection(int width, int height) {
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width * height];
        int sourceWidth = panorama.getWidth();
        int sourceHeight = panorama.getHeight();

        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double rollRad = Math.toRadians(roll);
        double cosPitch = Math.cos(pitchRad);
        double sinPitch = Math.sin(pitchRad);
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
                pixels[y * width + x] = panorama.getPixel(
                        wrap((int) (sample.u * sourceWidth), sourceWidth),
                        clamp((int) (sample.v * sourceHeight), 0, sourceHeight - 1));
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

    private void markDirty() {
        dirty = true;
        invalidate();
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
}
