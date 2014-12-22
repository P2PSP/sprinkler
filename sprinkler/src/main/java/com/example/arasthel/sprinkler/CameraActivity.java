package com.example.arasthel.sprinkler;

import android.app.Activity;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.net.LocalSocket;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Window;

import com.example.arasthel.sprinkler.mp4.MP4Config;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.DatagramSocketImpl;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.Buffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;


public class CameraActivity extends Activity implements SurfaceHolder.Callback {

    SurfaceView cameraSurface;

    Camera camera;

    List<Camera.Size> previewSizes;

    DatagramSocket socket;

    MediaRecorder mediaRecorder;

    ParcelFileDescriptor parcelRead, parcelWrite;

    private final static String TEST_FILE_PATH = Environment.getExternalStorageDirectory().getAbsolutePath()+"/prueba.mp4";

    public static boolean running = false;

    private Camera.Size cameraSize;

    private String pps, sps, profile;

    private InputStream is;

    private byte[] header = new byte[5];

    private byte[] buffer;

    private int naluLength;

    private FileOutputStream fos;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_camera_acitvity);

        cameraSurface = (SurfaceView) findViewById(R.id.camera_surface);

        camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK);

        previewSizes = camera.getParameters().getSupportedPreviewSizes();
        cameraSize = determinePreviewSize(false, 1280, 720);

        /*Camera.Parameters parameters = camera.getParameters();
        parameters.setPreviewSize(cameraSize.width, cameraSize.height);
        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
        parameters.setFlashMode(Camera.Parameters.FLASH_MODE_ON);
        camera.setParameters(parameters);*/

        camera.unlock();

        mediaRecorder = new MediaRecorder();

        configureMediaRecorder();


        //mediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_LOW));

        Log.d("SIZE", cameraSize.width+"x"+cameraSize.height);

        new ControlThread().start();

    }

    private void configureMediaRecorder() {
        mediaRecorder.setCamera(camera);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
        //mediaRecorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT);

        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);


        mediaRecorder.setVideoSize(1280, 720);
        mediaRecorder.setVideoEncodingBitRate(17000000);
        mediaRecorder.setVideoFrameRate(30);

        //CamcorderProfile profile = CamcorderProfile.get(CamcorderProfile.QUALITY_1080P);


        //mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H263);
    }

    private class ControlThread extends Thread {
        @Override
        public void run() {
            super.run();
            try {
                socket = new DatagramSocket();
                socket.connect(InetAddress.getByName("192.168.10.169"), 8080);

                long lastPass = 0;

                MessageDigest digest = MessageDigest.getInstance("MD5");
                digest.update("hackme".getBytes());

                byte[] passwordBytes = digest.digest();

                StringBuffer hexString = new StringBuffer();
                for (int i = 0; i < passwordBytes.length; i++) {
                    hexString.append(Integer.toHexString(0xFF & passwordBytes[i]));
                }

                /*socket.getOutputStream().write(hexString.toString().getBytes("UTF-8"));

                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                char[] result = new char[32];
                bufferedReader.read(result);
                if (result != null) {
                    Log.d("Splitter", "Connection accepted");
                }*/

                running = true;

                new StreamingThread().start();

                /*DatagramPacket ppsPacket = new DatagramPacket(pps.getBytes(), pps.length());
                DatagramPacket spsPacket = new DatagramPacket(sps.getBytes(), sps.length());

                while(running) {
                    if((System.currentTimeMillis()) - lastPass >= 3000) {
                        lastPass = System.currentTimeMillis();
                        Log.d("SPS", "SPS & PPS sent");
                        socket.send(ppsPacket);
                        socket.send(spsPacket);
                        fos.write(pps.getBytes());
                        fos.write(sps.getBytes());
                    }
                }*/

            } catch (IOException e) {
                e.printStackTrace();
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

                int read = 0;

                DatagramPacket packet;

                /*do {
                    // Read NALU header
                    fill(header, 0, 5);
                    naluLength = header[3]&0xFF | (header[2]&0xFF)<<8 | (header[1]&0xFF)<<16 | (header[0]&0xFF)<<24;
                    Log.d("NALU", "LENGTH: "+naluLength);
                    if (naluLength>100000 || naluLength<0) resync();

                    buffer = new byte[5+naluLength];
                    for(int i = 0; i < 5; i++) {
                        buffer[i] = header[i];
                    }
                    read = fill(buffer, 5, naluLength-1);
                    packet = new DatagramPacket(buffer, buffer.length);
                    fos.write(buffer);
                    socket.send(packet);

                } while(read > 0);*/

                do {
                    // Read H263 chuncks
                    buffer = new byte[256];
                    read = fill(buffer, 0, buffer.length);
                    packet = new DatagramPacket(buffer, buffer.length);
                    //fos.write(buffer);
                    socket.send(packet);
                    //Log.d("SPRINKLER", "Sent chunk of "+read+" bytes");
                } while(running);

                parcelRead.close();
                parcelWrite.close();

                //socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }


        }
    }

    private int fill(byte[] buffer, int offset,int length) throws IOException {
        int sum = 0, len;
        while (sum<length) {
            len = is.read(buffer, offset + sum, length - sum);
            if (len<0) {
                throw new IOException("End of stream");
            }
            else sum+=len;
        }
        return sum;
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
        //camera.lock();
        camera.release();
    }

    private void resync() throws IOException {
        int type;

        Log.e("ERROR","Packetizer out of sync ! Let's try to fix that...(NAL length: "+naluLength+")");

        while (true) {

            header[0] = header[1];
            header[1] = header[2];
            header[2] = header[3];
            header[3] = header[4];
            header[4] = (byte) is.read();

            type = header[4]&0x1F;

            if (type == 5 || type == 1) {
                naluLength = header[3]&0xFF | (header[2]&0xFF)<<8 | (header[1]&0xFF)<<16 | (header[0]&0xFF)<<24;
                if (naluLength>0 && naluLength<100000) {
                    Log.e("ERROR","A NAL unit may have been found in the bit stream !");
                    break;
                }
                if (naluLength==0) {
                    Log.e("ERROR","NAL unit with NULL size found...");
                } else if (header[3]==0xFF && header[2]==0xFF && header[1]==0xFF && header[0]==0xFF) {
                    Log.e("ERROR","NAL unit with 0xFFFFFFFF size found...");
                }
            }

        }

    }

}
