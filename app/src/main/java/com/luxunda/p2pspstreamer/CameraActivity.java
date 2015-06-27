package com.luxunda.p2pspstreamer;

import android.app.*;
import android.hardware.Camera;
import android.text.*;
import android.preference.*;
import java.util.*;
import android.content.*;
import android.util.Log;
import android.view.*;
import android.widget.*;
import android.os.*;
import io.kickflip.sdk.*;
import io.kickflip.sdk.api.json.Stream;
import io.kickflip.sdk.av.*;
import io.kickflip.sdk.exception.KickflipException;

public class CameraActivity extends Activity implements View.OnClickListener, BroadcastListener
{
    private EditText feedEditText;
    private EditText portEditText;
    private EditText urlEditText;
    private Button connectButton;
    private Spinner resolutionSpinner;
    private EditText protocolText;
    private EditText videoBitRateText;
    private EditText audioBitRateText;
    private EditText segmentDurationText;

    private List<Camera.Size> cameraPreviewSizes;

    private Handler handler;

    private boolean checkData() {
        return !TextUtils.isEmpty(this.urlEditText.getText()) && TextUtils.isDigitsOnly(this.portEditText.getText()) && !TextUtils.isEmpty(this.feedEditText.getText());
    }

    private Map<String, String> getExtraInfo() {
        final SharedPreferences defaultSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        final HashMap<String, String> hashMap = new HashMap<>();
        hashMap.put("url", defaultSharedPreferences.getString("url", null));
        hashMap.put("port", defaultSharedPreferences.getString("port", null));
        hashMap.put("feed", defaultSharedPreferences.getString("feed", null));
        hashMap.put("protocol", defaultSharedPreferences.getString("protocol", "http"));
        return hashMap;
    }

    private void restoreData() {
        final SharedPreferences defaultSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        urlEditText.setText(defaultSharedPreferences.getString("url", ""));
        portEditText.setText(defaultSharedPreferences.getString("port", ""));
        feedEditText.setText(defaultSharedPreferences.getString("feed", ""));

        protocolText.setText(defaultSharedPreferences.getString("protocol", "http"));

        videoBitRateText.setText(String.valueOf(defaultSharedPreferences.getInt("video_bitrate", 800)));
        audioBitRateText.setText(String.valueOf(defaultSharedPreferences.getInt("audio_bitrate", 64)));
        segmentDurationText.setText(String.valueOf(defaultSharedPreferences.getInt("segment_duration", 1)));

        resolutionSpinner.setSelection(defaultSharedPreferences.getInt("selected_resolution", 0));
    }

    private void saveData() {
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString("url", this.urlEditText.getText().toString())
                .putString("port", this.portEditText.getText().toString())
                .putString("feed", this.feedEditText.getText().toString())
                .putInt("selected_resolution", resolutionSpinner.getSelectedItemPosition())
                .putInt("video_bitrate", Integer.parseInt(videoBitRateText.getText().toString()))
                .putInt("audio_bitrate", Integer.parseInt(audioBitRateText.getText().toString()))
                .putInt("segment_duration", Integer.parseInt(segmentDurationText.getText().toString()))
                .putString("protocol", protocolText.getText().toString())
                .apply();
    }

    public void onClick(final View view) {
        switch (view.getId()) {
            case R.id.connect_button:
                if (this.checkData()) {
                    this.saveData();
                    this.startRecordingActivity();
                    return;
                }
                Toast.makeText(this, "El formato de los datos es incorrecto, revíselo y vuelva a intentarlo.", Toast.LENGTH_SHORT).show();
                break;

        }
    }

    protected void onCreate(final Bundle bundle) {
        super.onCreate(bundle);
        this.setContentView(R.layout.activity_main);

        this.urlEditText = (EditText)this.findViewById(R.id.config_url);
        this.portEditText = (EditText)this.findViewById(R.id.config_port);
        this.feedEditText = (EditText)this.findViewById(R.id.config_feed);

        resolutionSpinner = (Spinner) findViewById(R.id.config_resolution);

        videoBitRateText = (EditText) findViewById(R.id.config_video_bitrate);
        audioBitRateText = (EditText) findViewById(R.id.config_audio_bitrate);
        segmentDurationText = (EditText) findViewById(R.id.config_segment_duration);

        protocolText = (EditText) findViewById(R.id.config_protocol);

        connectButton = (Button) findViewById(R.id.connect_button);
        connectButton.setOnClickListener(this);

        handler = new Handler(getMainLooper());

        loadCameraPreviewSizes();

        restoreData();
    }

    private void loadCameraPreviewSizes() {
        Camera camera = Camera.open(0);
        cameraPreviewSizes = camera.getParameters().getSupportedVideoSizes();

        if (cameraPreviewSizes == null) {
            cameraPreviewSizes = camera.getParameters().getSupportedPreviewSizes();
        }

        camera.release();

        resolutionSpinner.setAdapter(new CameraSizesArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cameraPreviewSizes));
    }

    public void startRecordingActivity() {
        Camera.Size resolution = (Camera.Size) resolutionSpinner.getSelectedItem();
        int videoBitRate = Integer.parseInt(videoBitRateText.getText().toString());
        int audioBitRate = Integer.parseInt(audioBitRateText.getText().toString());
        int duration = Integer.parseInt(segmentDurationText.getText().toString());

        // Streaming session config and optimal values
        Kickflip.setSessionConfig(new SessionConfig.Builder(Environment.getExternalStorageDirectory() + "/KickFlip/video.m3u8")
                .withAdaptiveStreaming(true)
                .withVideoResolution(resolution.width, resolution.height) // 1280x720 (720p)
                .withVideoBitrate(videoBitRate * 1024) // 800 kbps
                .withAudioBitrate(audioBitRate * 1024) // 64 kbps
                .withHlsSegmentDuration(duration) // 1s duration to minimize delay
                .withExtraInfo(this.getExtraInfo()).build());

        Kickflip.startBroadcastActivity(this, this);
    }


    @Override
    public void onBroadcastStart() {

    }

    @Override
    public void onBroadcastLive(Stream stream) {

    }

    @Override
    public void onBroadcastStop() {
        // This is needed to let the camera close and open again
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                connectButton.setEnabled(false);
            }
        });

        new Thread(new Runnable() {
            @Override
            public void run() {
                Camera camera = null;
                while(camera == null) {
                    try {
                        camera = Camera.open();
                    } catch (Exception e) {
                        // Camera si not ready yet
                    }
                }

                camera.release();

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        connectButton.setEnabled(true);
                    }
                });
            }
        }).start();

    }

    @Override
    public void onBroadcastError(KickflipException error) {
        onBroadcastStop();
    }
}
