package com.luxunda.p2pspstreamer;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Toast;

import io.kickflip.sdk.Kickflip;
import io.kickflip.sdk.api.json.Stream;
import io.kickflip.sdk.av.BroadcastListener;
import io.kickflip.sdk.av.SessionConfig;
import io.kickflip.sdk.exception.KickflipException;

/**
 * Created by Arasthel on 20/02/15.
 */
public class CameraActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SessionConfig config = new SessionConfig.Builder(Environment.getExternalStorageDirectory()+"/KickFlip/video.m3u8")
                .withAdaptiveStreaming(true)
                .withVideoResolution(1280, 720)
                .withVideoBitrate(2 * 1000 * 1000)
                .withAudioBitrate(192 * 1000)
                .withHlsSegmentDuration(1)
                .build();
        Kickflip.setSessionConfig(config);
        Kickflip.startBroadcastActivity(this, new BroadcastListener() {
            @Override
            public void onBroadcastStart() {
                Toast.makeText(CameraActivity.this, "Recording started", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onBroadcastLive(Stream stream) {

            }

            @Override
            public void onBroadcastStop() {

            }

            @Override
            public void onBroadcastError(KickflipException error) {

            }
        });
    }
}
