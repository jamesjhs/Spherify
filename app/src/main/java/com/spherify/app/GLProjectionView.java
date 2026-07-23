/*
 * GLProjectionView.java
 *
 * Educational overview:
 * GLProjectionView is the interactive projection engine for Spherify. It is a
 * GLSurfaceView that can display an equirectangular Photo Sphere as either an
 * immersive sphere view or a Tiny Planet projection. It also exports the current
 * view to bitmap files using a CPU renderer that mirrors the OpenGL math.
 *
 * Data flow:
 * MainActivity decodes a LibraryItem image into a Bitmap -> setPanorama() stores
 * pixel data and sends the bitmap to ProjectionRenderer on the GL thread ->
 * touch/adjustment methods update yaw, pitch, roll, horizon, and zoom ->
 * pushStateToRenderer() queues state for OpenGL uniforms -> ProjectionRenderer
 * draws the view using shader math. During export, exportProjection() calls
 * renderProjectionOnCpu(), writes a full-size PNG and thumbnail JPEG, and
 * returns ProjectionExport for SpherifyLibrary to save.
 *
 * External files/functions:
 * Reads Bitmap pixels supplied by MainActivity/SpherifyLibrary.
 * Writes temporary export files under getExternalFilesDir(Pictures)/Spherify.
 * Uses Android OpenGL ES 2.0 APIs through GLES20 and GLSurfaceView.Renderer.
 * Uses ProjectionExport as the file handoff object for saved variants.
 *
 * Imports/dependencies:
 * Bitmap/File/FileOutputStream support export and thumbnail creation.
 * GLES20/GLSurfaceView/GLUtils provide GPU rendering and texture uploads.
 * Bundle saves/restores projection state across Activity recreation.
 * MotionEvent/ScaleGestureDetector translate gestures into projection changes.
 * FloatBuffer/ByteBuffer feed a full-screen quad into the vertex shader.
 *
 * Key variables:
 * renderer: nested OpenGL renderer that owns shader program and texture state.
 * panorama/panoramaPixels/panoramaWidth/panoramaHeight: source image data used
 * by GPU preview and CPU export.
 * sourceTinyPlanet: true when the input is already a tiny-planet-like square.
 * mode: current output projection, SPHERE or TINY_PLANET.
 * centerYaw/centerPitch/centerRoll: accumulated recentering offsets.
 * yaw/pitch/roll/horizonOffset/cameraDistance/zoom: live interaction and
 * adjustment state.
 * last* and rotatingGesture fields: gesture bookkeeping between touch events.
 */
package com.spherify.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.Bundle;
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
import java.text.SimpleDateFormat;
import java.util.Date;
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
    private static final float MIN_CAMERA_DISTANCE = 0.35f;
    private static final float MAX_CAMERA_DISTANCE = 2.5f;
    private static final float MAX_VIEW_PITCH_DEGREES = 89f;

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
    private float cameraDistance = 1f;
    private float zoom = 1f;
    private float lastX;
    private float lastY;
    private float lastPointerAngle;
    private float lastPointerFocusX;
    private float lastPointerFocusY;
    private boolean rotatingGesture;

    /*
     * Function: GLProjectionView(Context)
     * Arguments: context is the Android owner used for resources and files.
     * Calls: the two-argument constructor with attrs set to null.
     * Flow: convenience constructor for programmatic creation from MainActivity.
     */
    public GLProjectionView(Context context) {
        this(context, null);
    }

    /*
     * Function: GLProjectionView(Context, AttributeSet)
     * Arguments: context supplies Android services; attrs would contain XML
     * attributes if inflated from a layout.
     * Calls: GLSurfaceView setup methods, ProjectionRenderer constructor,
     * setRenderer(), setRenderMode(), and ScaleGestureDetector.
     * Flow: configure an OpenGL ES 2 view, preserve context on pause, install the
     * renderer, use on-demand rendering, and create pinch-zoom handling.
     */
    public GLProjectionView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        renderer = new ProjectionRenderer();
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        setFocusable(true);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            /*
             * Function: onScale
             * Arguments: detector provides the pinch scale factor.
             * Calls: clamp(), getMinimumZoom(), and pushStateToRenderer().
             * Flow: multiply zoom by the pinch factor, keep it within valid
             * limits for the current mode, and redraw with the new state.
             */
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                zoom = clamp(zoom * detector.getScaleFactor(), getMinimumZoom(), 5f);
                pushStateToRenderer();
                return true;
            }
        });
    }

    /*
     * Function: setPanorama
     * Arguments: panorama is the source bitmap; sourceProjection describes how
     * the app should interpret it ("sphere", "tinyplanet", or "flat").
     * Calls: Bitmap.getPixels(), isSquareish(), queueEvent(), renderer.setPanorama(),
     * and pushStateToRenderer().
     * Flow: store dimensions and CPU pixels for export, infer source projection,
     * reset orientation state, queue texture upload on the GL thread, and redraw.
     */
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
        cameraDistance = 1f;
        boolean currentSourceTinyPlanet = sourceTinyPlanet;
        queueEvent(() -> renderer.setPanorama(panorama, currentSourceTinyPlanet));
        pushStateToRenderer();
    }

    /*
     * Function: getMode
     * Arguments: none.
     * Calls: no external functions.
     * Flow: return the current projection mode for MainActivity labels and save
     * decisions.
     */
    public Mode getMode() {
        return mode;
    }

    /*
     * Function: saveProjectionState
     * Arguments: outState receives values; prefix namespaces keys within Bundle.
     * Calls: Bundle.putString() and Bundle.putFloat().
     * Flow: write every user-adjustable projection value so Android recreation
     * can restore the same view.
     */
    public void saveProjectionState(Bundle outState, String prefix) {
        outState.putString(prefix + "mode", mode.name());
        outState.putFloat(prefix + "centerYaw", centerYaw);
        outState.putFloat(prefix + "centerPitch", centerPitch);
        outState.putFloat(prefix + "centerRoll", centerRoll);
        outState.putFloat(prefix + "yaw", yaw);
        outState.putFloat(prefix + "pitch", pitch);
        outState.putFloat(prefix + "roll", roll);
        outState.putFloat(prefix + "horizonOffset", horizonOffset);
        outState.putFloat(prefix + "cameraDistance", cameraDistance);
        outState.putFloat(prefix + "zoom", zoom);
    }

    /*
     * Function: restoreProjectionState
     * Arguments: savedState is a Bundle from Android; prefix matches the save key
     * namespace.
     * Calls: Bundle getters, Mode.valueOf(), getMinimumZoom(), and pushStateToRenderer().
     * Flow: read saved projection values with defaults, tolerate unknown mode
     * strings, clamp zoom to valid mode limits, and redraw.
     */
    public void restoreProjectionState(Bundle savedState, String prefix) {
        String modeName = savedState.getString(prefix + "mode");
        if (modeName != null) {
            try {
                mode = Mode.valueOf(modeName);
            } catch (IllegalArgumentException ignored) {
                mode = Mode.SPHERE;
            }
        }
        centerYaw = savedState.getFloat(prefix + "centerYaw", 0f);
        centerPitch = clamp(
                savedState.getFloat(prefix + "centerPitch", 0f),
                -MAX_VIEW_PITCH_DEGREES,
                MAX_VIEW_PITCH_DEGREES);
        centerRoll = savedState.getFloat(prefix + "centerRoll", 0f);
        yaw = savedState.getFloat(prefix + "yaw", 0f);
        pitch = clamp(
                savedState.getFloat(prefix + "pitch", 0f),
                -MAX_VIEW_PITCH_DEGREES,
                MAX_VIEW_PITCH_DEGREES);
        roll = savedState.getFloat(prefix + "roll", 0f);
        horizonOffset = savedState.getFloat(prefix + "horizonOffset", 0f);
        cameraDistance = clamp(
                savedState.getFloat(prefix + "cameraDistance", 1f),
                MIN_CAMERA_DISTANCE,
                MAX_CAMERA_DISTANCE);
        zoom = Math.max(savedState.getFloat(prefix + "zoom", 1f), getMinimumZoom());
        pushStateToRenderer();
    }

    /*
     * Function: setMode
     * Arguments: mode is the requested output projection.
     * Calls: getMinimumZoom() and pushStateToRenderer().
     * Flow: switch projection, ensure zoom remains valid, clear roll adjustment,
     * and redraw.
     */
    public void setMode(Mode mode) {
        this.mode = mode;
        zoom = Math.max(zoom, getMinimumZoom());
        roll = 0f;
        pushStateToRenderer();
    }

    /*
     * Function: getStatusText
     * Arguments: none.
     * Calls: no external functions.
     * Flow: provide a short user-facing description of the active mode.
     */
    public String getStatusText() {
        return mode == Mode.SPHERE ? "Photo Sphere" : "Tiny Planet";
    }

    /*
     * Function: toggleMode
     * Arguments: none.
     * Calls: getMinimumZoom() and pushStateToRenderer().
     * Flow: flip between sphere and tiny-planet rendering, normalize zoom/roll,
     * and redraw for MainActivity's mode button.
     */
    public void toggleMode() {
        mode = mode == Mode.SPHERE ? Mode.TINY_PLANET : Mode.SPHERE;
        zoom = Math.max(zoom, getMinimumZoom());
        roll = 0f;
        pushStateToRenderer();
    }

    /*
     * Function: recentre
     * Arguments: none.
     * Calls: normalizeDegrees(), clamp(), and pushStateToRenderer().
     * Flow: fold current interactive yaw/pitch/roll into the center offsets and
     * reset live deltas, making the current view the new neutral reference.
     */
    public void recentre() {
        centerYaw = normalizeDegrees(centerYaw + yaw);
        centerPitch = clamp(centerPitch + pitch, -MAX_VIEW_PITCH_DEGREES, MAX_VIEW_PITCH_DEGREES);
        centerRoll = normalizeDegrees(centerRoll + roll);
        yaw = 0f;
        pitch = 0f;
        roll = 0f;
        pushStateToRenderer();
    }

    /*
     * Function: resetView
     * Arguments: none.
     * Calls: pushStateToRenderer().
     * Flow: clear live orientation, horizon, camera distance, and zoom
     * adjustments without changing the currently loaded panorama or mode.
     */
    public void resetView() {
        yaw = 0f;
        pitch = 0f;
        roll = 0f;
        horizonOffset = 0f;
        cameraDistance = 1f;
        zoom = 1f;
        pushStateToRenderer();
    }

    /*
     * Function: getCameraDistancePercent
     * Arguments: none.
     * Calls: no external functions.
     * Flow: expose the virtual camera distance as a percent value for the
     * Adjust dialog.
     */
    public float getCameraDistancePercent() {
        return cameraDistance * 100f;
    }

    /*
     * Function: setCameraDistancePercent
     * Arguments: percent is the requested virtual camera distance from the
     * Adjust dialog.
     * Calls: clamp() and pushStateToRenderer().
     * Flow: convert the percent value to the internal distance multiplier, bound
     * it to useful limits, and redraw.
     */
    public void setCameraDistancePercent(float percent) {
        cameraDistance = clamp(percent / 100f, MIN_CAMERA_DISTANCE, MAX_CAMERA_DISTANCE);
        pushStateToRenderer();
    }

    /*
     * Function: getImageRotationDegrees
     * Arguments: none.
     * Calls: no external functions.
     * Flow: expose the current roll adjustment for the Adjust dialog.
     */
    public float getImageRotationDegrees() {
        return roll;
    }

    /*
     * Function: setImageRotationDegrees
     * Arguments: degrees is a roll angle from the Adjust dialog.
     * Calls: normalizeDegrees() and pushStateToRenderer().
     * Flow: normalize the angle to -180..180 degrees and redraw.
     */
    public void setImageRotationDegrees(float degrees) {
        roll = normalizeDegrees(degrees);
        pushStateToRenderer();
    }

    /*
     * Function: getEyeElevationDegrees
     * Arguments: none.
     * Calls: no external functions.
     * Flow: expose the horizon/elevation offset for the Adjust dialog.
     */
    public float getEyeElevationDegrees() {
        return horizonOffset;
    }

    /*
     * Function: setEyeElevationDegrees
     * Arguments: degrees is the desired horizon offset.
     * Calls: clamp() and pushStateToRenderer().
     * Flow: limit the horizon offset to a usable range and redraw.
     */
    public void setEyeElevationDegrees(float degrees) {
        horizonOffset = clamp(degrees, -75f, 75f);
        pushStateToRenderer();
    }

    /*
     * Function: exportProjection
     * Arguments: none.
     * Calls: renderProjectionOnCpu(), Bitmap.createScaledBitmap(), getExternalFilesDir(),
     * writeBitmap(), and ProjectionExport constructor.
     * Flow: render the current projection to a full-size bitmap, derive a
     * thumbnail, write both to export files, recycle temporary bitmaps, and
     * return their File handles.
     */
    public ProjectionExport exportProjection() throws IOException {
        Bitmap image = renderProjectionOnCpu(EXPORT_SIZE, EXPORT_SIZE);
        Bitmap thumbnail = Bitmap.createScaledBitmap(image, THUMBNAIL_SIZE, THUMBNAIL_SIZE, true);

        File directory = new File(
                getContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "Spherify");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("could not create export directory");
        }

        String stamp = new SimpleDateFormat("yyMMddss-SSS", Locale.US).format(new Date());
        String prefix = mode == Mode.SPHERE ? "photosphere" : "tinyplanet";
        File imageFile = new File(directory, prefix + "-" + stamp + ".png");
        File thumbnailFile = new File(directory, prefix + "-" + stamp + "-thumb.jpg");

        writeBitmap(image, Bitmap.CompressFormat.PNG, 100, imageFile);
        writeBitmap(thumbnail, Bitmap.CompressFormat.JPEG, 86, thumbnailFile);

        image.recycle();
        thumbnail.recycle();
        return new ProjectionExport(imageFile, thumbnailFile);
    }

    /*
     * Function: onTouchEvent
     * Arguments: event is an Android touch event containing pointer positions.
     * Calls: ScaleGestureDetector.onTouchEvent(), handleTwoFingerGesture(),
     * applyDragDelta(), and MotionEvent accessors.
     * Flow: let pinch zoom process first, handle pointer changes, route two-
     * finger movement to adjustment logic, and route one-finger drag to pan or
     * tiny-planet spin depending on mode.
     */
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

    /*
     * Function: handleTwoFingerGesture
     * Arguments: event contains two active pointer positions.
     * Calls: Math.atan2(), Math.toDegrees(), normalizeDegrees(),
     * applyTwoFingerFocusDelta(), and pushStateToRenderer().
     * Flow: track the angle and midpoint between two fingers so the user can
     * rotate the projection while also adjusting horizon and camera distance.
     */
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
        applyTwoFingerFocusDelta(focusDx, focusDy);
    }

    /*
     * Function: applyTwoFingerFocusDelta
     * Arguments: dx/dy are the screen-space movement deltas of the two-finger
     * midpoint in pixels.
     * Calls: getWidth(), getHeight(), clamp(), getMinimumZoom(), and
     * pushStateToRenderer().
     * Flow: translate two-finger midpoint movement into adjustment controls:
     * horizontal movement changes camera distance, vertical movement changes the
     * horizon reference. Pinch zoom and two-finger rotation are handled
     * separately by the scale detector and angle tracking.
     */
    private void applyTwoFingerFocusDelta(float dx, float dy) {
        float widthReference = Math.max(1f, getWidth());
        float heightReference = Math.max(1f, Math.min(getWidth(), getHeight()));
        cameraDistance = clamp(
                cameraDistance + dx / widthReference * (MAX_CAMERA_DISTANCE - MIN_CAMERA_DISTANCE),
                MIN_CAMERA_DISTANCE,
                MAX_CAMERA_DISTANCE);
        horizonOffset = clamp(horizonOffset - dy / heightReference * 120f, -75f, 75f);
        pushStateToRenderer();
    }

    /*
     * Function: applyDragDelta
     * Arguments: dx/dy are screen-space movement deltas in pixels.
     * Calls: getWidth(), getHeight(), normalizeDegrees(), clamp(), and
     * pushStateToRenderer().
     * Flow: translate touch movement into camera yaw/pitch changes; both
     * projection modes clamp pitch to avoid flipping the viewport through a pole.
     */
    private void applyDragDelta(float dx, float dy) {
        float verticalDragReference = Math.max(1f, Math.min(getWidth(), getHeight()));
        if (mode == Mode.SPHERE) {
            yaw = normalizeDegrees(yaw + dx / Math.max(1f, getWidth()) * 180f / zoom);
            pitch = clamp(
                    pitch - dy / verticalDragReference * 120f / zoom,
                    -MAX_VIEW_PITCH_DEGREES,
                    MAX_VIEW_PITCH_DEGREES);
        } else {
            yaw = normalizeDegrees(yaw + dx / Math.max(1f, getWidth()) * 180f / zoom);
            pitch = clamp(
                    pitch - dy / verticalDragReference * 120f / zoom,
                    -MAX_VIEW_PITCH_DEGREES,
                    MAX_VIEW_PITCH_DEGREES);
        }
        pushStateToRenderer();
    }

    /*
     * Function: pushStateToRenderer
     * Arguments: none.
     * Calls: getEffectiveYaw/Pitch/Roll(), queueEvent(), renderer.setState(), and
     * requestRender().
     * Flow: snapshot UI-thread projection state, send it safely to the GL thread,
     * then ask GLSurfaceView to draw a new frame.
     */
    private void pushStateToRenderer() {
        Mode currentMode = mode;
        float currentYaw = getEffectiveYaw();
        float currentPitch = getEffectivePitch();
        float currentRoll = getEffectiveRoll();
        float currentHorizonOffset = horizonOffset;
        float currentCameraDistance = cameraDistance;
        float currentZoom = zoom;
        queueEvent(() -> renderer.setState(
                currentMode,
                currentYaw,
                currentPitch,
                currentRoll,
                currentHorizonOffset,
                currentCameraDistance,
                currentZoom));
        requestRender();
    }

    /*
     * Function: renderProjectionOnCpu
     * Arguments: width/height are output bitmap dimensions.
     * Calls: sampleSphere(), sampleTinyPlanet(), samplePanorama(),
     * sampleTinyPlanetSource(), and Bitmap.setPixels().
     * Flow: for every output pixel, compute the 3D sample direction for the
     * active mode, sample the source image pixels, and write the final bitmap.
     */
    private Bitmap renderProjectionOnCpu(int width, int height) {
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width * height];

        double yawRad = Math.toRadians(getEffectiveYaw());
        double pitchRad = Math.toRadians(getEffectivePitch());
        double rollRad = Math.toRadians(getEffectiveRoll());
        double horizonOffsetRad = Math.toRadians(horizonOffset);
        double currentCameraDistance = cameraDistance;
        double cosRoll = Math.cos(rollRad);
        double sinRoll = Math.sin(rollRad);
        double aspect = width / (double) height;
        boolean flipSourceVertically = mode == Mode.TINY_PLANET;

        for (int y = 0; y < height; y++) {
            double rowPosition = 2.0 * y / Math.max(1, height - 1);
            double ny = mode == Mode.TINY_PLANET ? 1.0 - rowPosition : rowPosition - 1.0;
            for (int x = 0; x < width; x++) {
                double nx = ((2.0 * x / Math.max(1, width - 1)) - 1.0) * aspect;
                Sample sample = mode == Mode.SPHERE
                        ? sampleSphere(nx, ny, yawRad, pitchRad, currentCameraDistance)
                        : sampleTinyPlanet(nx, ny, yawRad, pitchRad, cosRoll, sinRoll, currentCameraDistance);
                pixels[y * width + x] = sourceTinyPlanet
                        ? sampleTinyPlanetSource(sample.x, sample.y, sample.z, horizonOffsetRad, flipSourceVertically)
                        : samplePanorama(sample.u, sample.v, horizonOffsetRad, flipSourceVertically);
            }
        }

        output.setPixels(pixels, 0, width, 0, 0, width, height);
        return output;
    }

    /*
     * Function: sampleSphere
     * Arguments: nx/ny are normalized screen coordinates; yawRad/pitchRad are
     * camera angles in radians; cameraDistance controls virtual camera distance.
     * Calls: getEffectiveRoll(), Math trig helpers, and directionToSample().
     * Flow: construct a viewing ray through the virtual camera plane and convert
     * that ray into source texture coordinates.
     */
    private Sample sampleSphere(double nx, double ny, double yawRad, double pitchRad, double cameraDistance) {
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

        double distanceSpread = spread * cameraDistance;
        double x = forwardX + rightX * rx * distanceSpread + upX * ry * distanceSpread;
        double y = forwardY + rightY * rx * distanceSpread + upY * ry * distanceSpread;
        double z = forwardZ + rightZ * rx * distanceSpread + upZ * ry * distanceSpread;
        double length = Math.sqrt(x * x + y * y + z * z);
        return directionToSample(x / length, y / length, z / length);
    }

    /*
     * Function: sampleTinyPlanet
     * Arguments: nx/ny are normalized screen coordinates; yawRad/pitchRad adjust
     * orientation; cosRoll/sinRoll are precomputed roll values; cameraDistance
     * controls how much of the projected scene is visible.
     * Calls: Math trig helpers and directionToSample().
     * Flow: map a 2D point through stereographic tiny-planet math into a 3D
     * direction, apply yaw/pitch, then convert to sampling coordinates.
     */
    private Sample sampleTinyPlanet(
            double nx,
            double ny,
            double yawRad,
            double pitchRad,
            double cosRoll,
            double sinRoll,
            double cameraDistance) {
        double rx = (nx * cosRoll - ny * sinRoll) * cameraDistance / zoom;
        double ry = (nx * sinRoll + ny * cosRoll) * cameraDistance / zoom;
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

    /*
     * Function: directionToSample
     * Arguments: x/y/z are a normalized 3D direction vector.
     * Calls: Math.atan2(), Math.asin(), and clamp().
     * Flow: convert direction to longitude/latitude, then to equirectangular u/v
     * coordinates while preserving the vector for tiny-planet source sampling.
     */
    private Sample directionToSample(double x, double y, double z) {
        double longitude = Math.atan2(x, z);
        double latitude = Math.asin(clamp(y, -1.0, 1.0));
        double u = (longitude / (2.0 * Math.PI)) + 0.5;
        double v = 0.5 - (latitude / Math.PI);
        return new Sample(u, v, x, y, z);
    }

    /*
     * Function: samplePanorama
     * Arguments: u/v are source coordinates; horizonOffsetRad shifts vertical
     * sampling; flipSourceVertically handles mode/source orientation.
     * Calls: wrap() and clamp().
     * Flow: map u/v into integer source pixels and return the panorama color.
     */
    private int samplePanorama(double u, double v, double horizonOffsetRad, boolean flipSourceVertically) {
        int sourceX = wrap((int) (u * panoramaWidth), panoramaWidth);
        v += horizonOffsetRad / Math.PI;
        if (flipSourceVertically) {
            v = 1.0 - v;
        }
        int sourceY = clamp((int) (v * panoramaHeight), 0, panoramaHeight - 1);
        return panoramaPixels[sourceY * panoramaWidth + sourceX];
    }

    /*
     * Function: sampleTinyPlanetSource
     * Arguments: x/y/z are a direction vector; horizonOffsetRad adjusts polar
     * sampling; flipSourceVertically controls vertical orientation.
     * Calls: Math.acos(), Math.tan(), Math.atan2(), clamp().
     * Flow: reverse-map a 3D direction into a square tiny-planet source image and
     * return the nearest source pixel.
     */
    private int sampleTinyPlanetSource(
            double x,
            double y,
            double z,
            double horizonOffsetRad,
            boolean flipSourceVertically) {
        double polar = clamp(Math.acos(clamp(y, -1.0, 1.0)) + horizonOffsetRad, 0.0, Math.PI - 0.001);
        double radius = Math.tan(polar * 0.5);
        double angle = Math.atan2(z, x);
        double u = 0.5 + Math.cos(angle) * radius * 0.5;
        double v = 0.5 + Math.sin(angle) * radius * 0.5;
        if (flipSourceVertically) {
            v = 1.0 - v;
        }
        int sourceX = clamp((int) (u * panoramaWidth), 0, panoramaWidth - 1);
        int sourceY = clamp((int) (v * panoramaHeight), 0, panoramaHeight - 1);
        return panoramaPixels[sourceY * panoramaWidth + sourceX];
    }

    /*
     * Function: getEffectiveYaw
     * Arguments: none.
     * Calls: normalizeDegrees().
     * Flow: combine recentered yaw with live yaw and normalize it for rendering.
     */
    private float getEffectiveYaw() {
        return normalizeDegrees(centerYaw + yaw);
    }

    /*
     * Function: getEffectivePitch
     * Arguments: none.
     * Calls: clamp().
     * Flow: combine recentered and live pitch, clamping near the poles so
     * viewport manipulation cannot invert the projected geometry.
     */
    private float getEffectivePitch() {
        return clamp(centerPitch + pitch, -MAX_VIEW_PITCH_DEGREES, MAX_VIEW_PITCH_DEGREES);
    }

    /*
     * Function: getEffectiveRoll
     * Arguments: none.
     * Calls: normalizeDegrees().
     * Flow: combine a sphere-specific base roll with center and live roll so
     * Photo Sphere images appear upright while still allowing user rotation.
     */
    private float getEffectiveRoll() {
        float baseRoll = mode == Mode.SPHERE ? PHOTOSPHERE_BASE_ROLL_DEGREES : 0f;
        return normalizeDegrees(baseRoll + centerRoll + roll);
    }

    /*
     * Function: getMinimumZoom
     * Arguments: none.
     * Calls: no external functions.
     * Flow: return mode-specific zoom lower bounds so projections remain useful.
     */
    private float getMinimumZoom() {
        return mode == Mode.TINY_PLANET ? 0.1f : 0.45f;
    }

    /*
     * Function: normalizeDegrees
     * Arguments: value is any angle in degrees.
     * Calls: modulo arithmetic only.
     * Flow: fold an angle into the -180..180 range for stable gesture math and
     * shader uniforms.
     */
    private static float normalizeDegrees(float value) {
        float normalized = value % 360f;
        if (normalized > 180f) {
            normalized -= 360f;
        } else if (normalized < -180f) {
            normalized += 360f;
        }
        return normalized;
    }

    /*
     * Function: writeBitmap
     * Arguments: bitmap supplies pixels; format and quality choose compression;
     * destination is the output file.
     * Calls: FileOutputStream and Bitmap.compress().
     * Flow: write compressed bitmap bytes and throw IOException if Android
     * reports compression failure.
     */
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

    /*
     * Function: wrap
     * Arguments: value is an index; maximum is the repeating range size.
     * Calls: modulo arithmetic only.
     * Flow: wrap horizontal panorama coordinates so longitude repeats cleanly.
     */
    private static int wrap(int value, int maximum) {
        int wrapped = value % maximum;
        return wrapped < 0 ? wrapped + maximum : wrapped;
    }

    /*
     * Function: clamp(float)
     * Arguments: value is bounded between minimum and maximum.
     * Calls: Math.max() and Math.min().
     * Flow: keep float parameters such as zoom, pitch, and horizon inside usable
     * ranges.
     */
    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /*
     * Function: clamp(int)
     * Arguments: value is bounded between minimum and maximum.
     * Calls: Math.max() and Math.min().
     * Flow: keep pixel indices inside source image bounds.
     */
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /*
     * Function: clamp(double)
     * Arguments: value is bounded between minimum and maximum.
     * Calls: Math.max() and Math.min().
     * Flow: keep projection math values inside valid trig and texture ranges.
     */
    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /*
     * Function: isSquareish
     * Arguments: width and height are source image dimensions.
     * Calls: Math.max() and Math.min().
     * Flow: infer tiny-planet-like source images by checking whether the aspect
     * ratio is close to square.
     */
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

        /*
         * Function: Sample constructor
         * Arguments: u/v are source texture coordinates; x/y/z preserve the 3D
         * direction that produced those coordinates.
         * Calls: no external functions.
         * Flow: store sampling coordinates so CPU export can choose between
         * equirectangular and tiny-planet source lookup.
         */
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
                "uniform float uCameraDistance;\n" +
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
                "  float distanceSpread = spread * uCameraDistance;\n" +
                "  return normalize(forward + right * r.x * distanceSpread + up * r.y * distanceSpread);\n" +
                "}\n" +
                "vec3 tinyPlanetDirection(vec2 p) {\n" +
                "  vec2 r = rollScreen(p) * uCameraDistance / uZoom;\n" +
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
        private int cameraDistanceHandle;
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
        private float cameraDistance = 1f;
        private float zoom = 1f;

        /*
         * Function: ProjectionRenderer constructor
         * Arguments: none.
         * Calls: FloatBuffer.position().
         * Flow: rewind the full-screen quad vertex buffer so OpenGL reads it from
         * the first coordinate during draw calls.
         */
        ProjectionRenderer() {
            vertices.position(0);
        }

        /*
         * Function: setPanorama
         * Arguments: panorama is the bitmap to upload; sourceTinyPlanet records
         * how shader sampling should interpret that texture.
         * Calls: uploadPendingPanorama() when the GL program already exists.
         * Flow: save the bitmap for GL-thread texture upload and immediately
         * upload it if the surface has already been created.
         */
        void setPanorama(Bitmap panorama, boolean sourceTinyPlanet) {
            pendingPanorama = panorama;
            this.sourceTinyPlanet = sourceTinyPlanet;
            if (program != 0) {
                uploadPendingPanorama();
            }
        }

        /*
         * Function: setState
         * Arguments: mode, yaw, pitch, roll, horizonOffset, cameraDistance, and
         * zoom are the latest projection controls from GLProjectionView.
         * Calls: no external functions.
         * Flow: copy UI-thread state into renderer fields. pushStateToRenderer()
         * calls this on the GL thread through queueEvent().
         */
        void setState(
                Mode mode,
                float yaw,
                float pitch,
                float roll,
                float horizonOffset,
                float cameraDistance,
                float zoom) {
            this.mode = mode;
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
            this.horizonOffset = horizonOffset;
            this.cameraDistance = cameraDistance;
            this.zoom = zoom;
        }

        /*
         * Function: onSurfaceCreated
         * Arguments: gl/config are provided by GLSurfaceView when an OpenGL
         * context is ready.
         * Calls: GLES20 clear/program/uniform lookups, buildProgram(), and
         * uploadPendingPanorama().
         * Flow: compile/link shaders, cache all attribute/uniform handles, set a
         * clear color, and upload any bitmap that arrived before the surface.
         */
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
            cameraDistanceHandle = GLES20.glGetUniformLocation(program, "uCameraDistance");
            aspectHandle = GLES20.glGetUniformLocation(program, "uAspect");
            horizonOffsetHandle = GLES20.glGetUniformLocation(program, "uHorizonOffset");
            sourceProjectionHandle = GLES20.glGetUniformLocation(program, "uSourceProjection");
            uploadPendingPanorama();
        }

        /*
         * Function: onSurfaceChanged
         * Arguments: width/height are the new GL viewport dimensions.
         * Calls: Math.max() and GLES20.glViewport().
         * Flow: store nonzero viewport dimensions and tell OpenGL where to draw.
         */
        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            viewportWidth = Math.max(1, width);
            viewportHeight = Math.max(1, height);
            GLES20.glViewport(0, 0, viewportWidth, viewportHeight);
        }

        /*
         * Function: onDrawFrame
         * Arguments: gl is the OpenGL interface supplied by GLSurfaceView.
         * Calls: GLES20 binding/uniform/attribute/draw APIs and Math.toRadians().
         * Flow: clear the frame, bind the panorama texture, push projection state
         * into shader uniforms, draw the full-screen quad, and disable the vertex
         * attribute afterward.
         */
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
            GLES20.glUniform1f(cameraDistanceHandle, cameraDistance);
            GLES20.glUniform1f(aspectHandle, viewportWidth / (float) viewportHeight);
            GLES20.glUniform1f(horizonOffsetHandle, (float) Math.toRadians(horizonOffset));
            GLES20.glUniform1i(sourceProjectionHandle, sourceTinyPlanet ? 1 : 0);

            GLES20.glEnableVertexAttribArray(positionHandle);
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertices);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionHandle);
        }

        /*
         * Function: uploadPendingPanorama
         * Arguments: none.
         * Calls: GLES20 texture allocation/parameter APIs and GLUtils.texImage2D().
         * Flow: if a bitmap is waiting, create a texture if needed, configure
         * filtering/wrapping, upload pixels to the GPU, and clear the pending
         * reference.
         */
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

        /*
         * Function: buildProgram
         * Arguments: vertexSource and fragmentSource are GLSL shader strings.
         * Calls: compileShader(), GLES20 program attach/link/status APIs.
         * Flow: compile both shaders, link them into an OpenGL program, verify
         * link success, and return the program id.
         */
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

        /*
         * Function: compileShader
         * Arguments: type is GLES20.GL_VERTEX_SHADER or GL_FRAGMENT_SHADER;
         * source is the GLSL source text.
         * Calls: GLES20 shader create/source/compile/status APIs.
         * Flow: compile one shader, throw a detailed exception on failure, and
         * return the shader id on success.
         */
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
