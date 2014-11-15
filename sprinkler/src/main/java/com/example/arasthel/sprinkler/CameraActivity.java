package com.example.arasthel.sprinkler;

import android.app.Activity;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;


public class CameraActivity extends Activity implements SurfaceHolder.Callback {

    SurfaceView cameraSurface;

    Camera camera;

    List<Camera.Size> previewSizes;

    Socket socket;

    LocalSocket senderSocket, receiverSocket;

    MediaRecorder mediaRecorder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_acitvity);

        cameraSurface = (SurfaceView) findViewById(R.id.camera_surface);

        camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK);

        cameraSurface.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        camera.unlock();

        mediaRecorder = new MediaRecorder();

        mediaRecorder.setCamera(camera);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.DEFAULT);
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT);

        /*mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.DEFAULT);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);*/
        //mediaRecorder.setVideoSize(1280, 720);
        //mediaRecorder.setVideoFrameRate(24);
        //mediaRecorder.setVideoEncodingBitRate((int) (500000*0.8));

        mediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_LOW));

        LocalServerSocket localServerSocket = null;
        try {
            receiverSocket = new LocalSocket();

            localServerSocket = new LocalServerSocket("Sprinkler");

            receiverSocket.connect(new LocalSocketAddress("Sprinkler"));
            receiverSocket.setReceiveBufferSize(500000);
            receiverSocket.setSoTimeout(3000);
            senderSocket = localServerSocket.accept();
            senderSocket.setSendBufferSize(500000);

            mediaRecorder.setOutputFile(receiverSocket.getFileDescriptor());
            //mediaRecorder.setOutputFile(Environment.getExternalStorageDirectory().getAbsolutePath()+"/prueba.mp4");
            cameraSurface.getHolder().addCallback(this);

        } catch (IOException e) {
            e.printStackTrace();
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    socket = new Socket("192.168.1.128", 8081);

                    //ParcelFileDescriptor parcelFileDescriptor = ParcelFileDescriptor.fromSocket(socket);

                    MessageDigest digest = MessageDigest.getInstance("MD5");
                    digest.update("hackme".getBytes());

                    byte[] passwordBytes = digest.digest();

                    StringBuffer hexString = new StringBuffer();
                    for (int i = 0; i < passwordBytes.length; i++)
                        hexString.append(Integer.toHexString(0xFF & passwordBytes[i]));

                    socket.getOutputStream().write(hexString.toString().getBytes("UTF-8"));

                    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                    char[] result = new char[32];
                    bufferedReader.read(result);
                    if (result != null) {
                        Log.d("Splitter", "Connection accepted");
                    }

                    //bufferedReader.close();

                    //mediaRecorder.setOutputFile(Environment.getExternalStorageDirectory().getAbsolutePath()+"/prueba.mp4");

                    //mediaRecorder.start();

                    byte[] buffer = new byte[1024];
                    while(senderSocket.getInputStream().read(buffer) > 0 ){
                        socket.getOutputStream().write(buffer);
                    }


                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (NoSuchAlgorithmException e) {
                    e.printStackTrace();
                }

            }
        }).start();

    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_camera_acitvity, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mediaRecorder.setPreviewDisplay(cameraSurface.getHolder().getSurface());
        try {
            mediaRecorder.prepare();
            mediaRecorder.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
        /*try {
            camera.setPreviewDisplay(holder);
        } catch (IOException e) {
            e.printStackTrace();
        }
        camera.startPreview();*/
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

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

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        //camera.stopPreview();
        //mediaRecorder.stop();
        //mediaRecorder.release();
    }
}
