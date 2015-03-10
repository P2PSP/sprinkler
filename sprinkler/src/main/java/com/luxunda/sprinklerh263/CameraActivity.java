package com.luxunda.sprinklerh263;

import android.app.Activity;
import android.hardware.Camera;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;

import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.impl.client.DefaultHttpClient;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;


public class CameraActivity extends Activity implements SurfaceHolder.Callback {

    SurfaceView cameraSurface;

    Camera camera;

    List<Camera.Size> previewSizes;

    MediaRecorder mediaRecorder;

    ParcelFileDescriptor parcelRead, parcelWrite;

    public static boolean running = false;

    private Camera.Size cameraSize;

    private InputStream is;

    public static final String SERVER_ADDRESS = "192.168.1.129";
    public static final int SERVER_PORT = 8000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_camera_acitvity);

        cameraSurface = (SurfaceView) findViewById(R.id.camera_surface);

        camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK);

        previewSizes = camera.getParameters().getSupportedPreviewSizes();
        cameraSize = determinePreviewSize(false, 1280, 720);

        camera.unlock();

        mediaRecorder = new MediaRecorder();

        configureMediaRecorder();

        new ControlThread().start();

    }

    private void configureMediaRecorder() {
        mediaRecorder.setCamera(camera);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);


        mediaRecorder.setVideoSize(1280, 720);
        mediaRecorder.setVideoEncodingBitRate(17000000);
        mediaRecorder.setVideoFrameRate(30);

        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H263);
    }

    private class ControlThread extends Thread {
        @Override
        public void run() {
            super.run();
            try {
                MessageDigest digest = MessageDigest.getInstance("MD5");
                digest.update("hackme".getBytes());

                byte[] passwordBytes = digest.digest();

                StringBuffer hexString = new StringBuffer();
                for (int i = 0; i < passwordBytes.length; i++) {
                    hexString.append(Integer.toHexString(0xFF & passwordBytes[i]));
                }

                // Send password to splitter, disabled for tests

                /*socket.getOutputStream().write(hexString.toString().getBytes("UTF-8"));

                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                char[] result = new char[32];
                bufferedReader.read(result);
                if (result != null) {
                    Log.d("Splitter", "Connection accepted");
                }*/

                running = true;

                new StreamingThread().start();

            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            }
        }
    }

    private class StreamingThread extends Thread {

        @Override
        public void run() {
            super.run();
            try {
                ParcelFileDescriptor[] descriptors = ParcelFileDescriptor.createPipe();
                parcelRead = new ParcelFileDescriptor(descriptors[0]);
                parcelWrite = new ParcelFileDescriptor(descriptors[1]);

                mediaRecorder.setOutputFile(parcelWrite.getFileDescriptor());

                cameraSurface.getHolder().addCallback(CameraActivity.this);

                is = new ParcelFileDescriptor.AutoCloseInputStream(parcelRead);

                // This will skip the MPEG4 header if this step fails we can't stream anything :(

                byte[] mpegHeader = {'m', 'd', 'a', 't'};
                byte[] headerBuffer = new byte[mpegHeader.length];
                try {
                    byte mpegHeaderBuffer[] = new byte[4];
                    // Skip all atoms preceding mdat atom
                    do {
                        is.read(headerBuffer);
                    } while(!Arrays.equals(mpegHeader, headerBuffer));
                } catch (IOException e) {
                    Log.e("ERROR","Couldn't skip mp4 header :/");
                    throw e;
                }

                Log.d("SPRINKLER", "MPEG HEADER SKIPPED");

                HttpPost post = new HttpPost("http://"+SERVER_ADDRESS+":"+SERVER_PORT+"/emit?channel=prueba");
                BasicHttpEntity httpEntity = new BasicHttpEntity();
                httpEntity.setChunked(true);
                httpEntity.setContentLength(-1);
                httpEntity.setContent(is);

                post.setEntity(httpEntity);

                HttpClient client = new DefaultHttpClient();
                client.execute(post);

            } catch (IOException e) {
                e.printStackTrace();
            }


        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mediaRecorder.setPreviewDisplay(holder.getSurface());
        try {
            mediaRecorder.prepare();
            mediaRecorder.start();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        cameraSurface.getHolder().setFixedSize(cameraSize.width, cameraSize.height);
    }

    protected Camera.Size determinePreviewSize(boolean portrait, int reqWidth, int reqHeight) {
        // Meaning of width and height is switched for preview when portrait,
        // while it is the same as user's view for surface and metrics.
        // That is, width must always be larger than height for setPreviewSize.
        int reqPreviewWidth; // requested width in terms of camera hardware
        int reqPreviewHeight; // requested height in terms of camera hardware
        if (portrait) {
            reqPreviewWidth = reqHeight;
            reqPreviewHeight = reqWidth;
        } else {
            reqPreviewWidth = reqWidth;
            reqPreviewHeight = reqHeight;
        }

        // Adjust surface size with the closest aspect-ratio
        float reqRatio = ((float) reqPreviewWidth) / reqPreviewHeight;
        float curRatio, deltaRatio;
        float deltaRatioMin = Float.MAX_VALUE;
        Camera.Size retSize = null;
        for (Camera.Size size : previewSizes) {
            curRatio = ((float) size.width) / size.height;
            deltaRatio = Math.abs(reqRatio - curRatio);
            if (deltaRatio < deltaRatioMin) {
                deltaRatioMin = deltaRatio;
                retSize = size;
            }
        }

        return retSize;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        holder.removeCallback(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            if(parcelRead != null) {
                parcelRead.close();
                parcelWrite.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        mediaRecorder.stop();
        mediaRecorder.release();
        camera.release();
    }

}
