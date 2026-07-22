/*
 * FlatImageActivity.java
 *
 * Educational overview:
 * FlatImageActivity is the simplest viewer in the app. It displays a normal
 * flat bitmap in an ImageView instead of sending it through GLProjectionView's
 * sphere/tiny-planet renderer. MainActivity opens this Activity for images
 * whose LibraryItem.projection is "flat", or for saved variants when the user
 * chooses "Open flat".
 *
 * Data flow:
 * MainActivity creates an Intent -> puts EXTRA_IMAGE_PATH with an absolute file
 * path -> FlatImageActivity reads that extra in onCreate() -> BitmapFactory
 * decodes the image file -> ImageView displays it.
 *
 * Imports/dependencies:
 * android.app.Activity provides the Android screen lifecycle.
 * android.graphics.Bitmap/BitmapFactory decode an image file into pixels.
 * android.os.Bundle carries saved lifecycle state, though this view does not
 * currently persist extra state.
 * android.view.Gravity and android.widget.ImageView configure display behavior.
 *
 * Key variables:
 * EXTRA_IMAGE_PATH: Intent extra key shared with MainActivity.
 * imageView: full-screen view that scales the bitmap to fit.
 * imagePath: absolute path supplied by the launching Intent.
 * bitmap: decoded pixels, or null if the file cannot be read as an image.
 */
package com.spherify.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageView;

public class FlatImageActivity extends Activity {
    static final String EXTRA_IMAGE_PATH = "com.spherify.app.EXTRA_IMAGE_PATH";

    /*
     * Function: onCreate
     * Arguments: savedInstanceState is Android lifecycle state. This Activity
     * does not currently read it because the image path comes from the Intent.
     * Calls: getIntent(), BitmapFactory.decodeFile(), ImageView setters, and
     * setContentView().
     * Flow: build a full-screen ImageView, read the image path extra, decode the
     * file if present, attach the bitmap, and make the ImageView the Activity UI.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ImageView imageView = new ImageView(this);
        imageView.setBackgroundColor(0xFF05070A);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setAdjustViewBounds(true);
        imageView.setForegroundGravity(Gravity.CENTER);
        setContentView(imageView);

        String imagePath = getIntent().getStringExtra(EXTRA_IMAGE_PATH);
        if (imagePath != null) {
            new Thread(() -> {
                Bitmap bmp = BitmapFactory.decodeFile(imagePath);
                runOnUiThread(() -> {
                    if (!isFinishing() && bmp != null) {
                        imageView.setImageBitmap(bmp);
                    }
                });
            }).start();
        }
    }
}
