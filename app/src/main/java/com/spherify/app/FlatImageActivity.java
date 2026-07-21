package com.spherify.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageView;

public class FlatImageActivity extends Activity {
    static final String EXTRA_IMAGE_PATH = "com.spherify.app.EXTRA_IMAGE_PATH";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ImageView imageView = new ImageView(this);
        imageView.setBackgroundColor(0xFF05070A);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setAdjustViewBounds(true);
        imageView.setForegroundGravity(Gravity.CENTER);

        String imagePath = getIntent().getStringExtra(EXTRA_IMAGE_PATH);
        Bitmap bitmap = imagePath == null ? null : BitmapFactory.decodeFile(imagePath);
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
        }
        setContentView(imageView);
    }
}
