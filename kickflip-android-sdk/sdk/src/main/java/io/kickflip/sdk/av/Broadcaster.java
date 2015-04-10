package io.kickflip.sdk.av;

import android.content.Context;
import android.util.Log;

import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.google.common.eventbus.DeadEvent;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;

import io.kickflip.sdk.FileUtils;
import io.kickflip.sdk.Kickflip;
import io.kickflip.sdk.api.json.HlsStream;
import io.kickflip.sdk.api.s3.S3BroadcastManager;
import io.kickflip.sdk.event.BroadcastIsBufferingEvent;
import io.kickflip.sdk.event.BroadcastIsLiveEvent;
import io.kickflip.sdk.event.HlsManifestWrittenEvent;
import io.kickflip.sdk.event.HlsSegmentWrittenEvent;
import io.kickflip.sdk.event.MuxerFinishedEvent;
import io.kickflip.sdk.event.UploadSegmentEvent;
import io.kickflip.sdk.event.StreamLocationAddedEvent;
import io.kickflip.sdk.event.ThumbnailWrittenEvent;

import static io.kickflip.sdk.Kickflip.isKitKat;

/**
 * Broadcasts HLS video and audio to <a href="https://kickflip.io">Kickflip.io</a>.
 * The lifetime of this class correspond to a single broadcast. When performing multiple broadcasts,
 * ensure reference to only one {@link io.kickflip.sdk.av.Broadcaster} is held at any one time.
 * {@link io.kickflip.sdk.fragment.BroadcastFragment} illustrates how to use Broadcaster in this pattern.
 * <p/>
 * Example Usage:
 * <p/>
 * <ol>
 * <li>Construct {@link Broadcaster()} with your Kickflip.io Client ID and Secret</li>
 * <li>Call {@link Broadcaster#setPreviewDisplay(io.kickflip.sdk.view.GLCameraView)} to assign a
 * {@link io.kickflip.sdk.view.GLCameraView} for displaying the live Camera feed.</li>
 * <li>Call {@link io.kickflip.sdk.av.Broadcaster#startRecording()} to begin broadcasting</li>
 * <li>Call {@link io.kickflip.sdk.av.Broadcaster#stopRecording()} to end the broadcast.</li>
 * </ol>
 *
 * @hide
 */
// TODO: Make HLS / RTMP Agnostic
public class Broadcaster extends AVRecorder {
    private static final String TAG = "Broadcaster";
    private static final boolean VERBOSE = true;
    private static final int MIN_BITRATE = 3 * 100 * 1000;              // 300 kbps
    private final String VOD_FILENAME = "vod.m3u8";
    private Context mContext;
    private HlsStream mStream;
    private HlsFileObserver mFileObserver;
    private LinkedBlockingQueue<File> mUploadQueue;
    private BroadcastListener mBroadcastListener;
    private EventBus mEventBus;
    private boolean mReadyToBroadcast;                                  // Kickflip user registered and endpoint ready
    private boolean mSentBroadcastLiveEvent;
    private int mVideoBitrate;
    private File mManifestSnapshotDir;                                  // Directory where manifest snapshots are stored
    private File mVodManifest;                                          // VOD HLS Manifest containing complete history
    private int mNumSegmentsWritten;
    private int mLastRealizedBandwidthBytesPerSec;                      // Bandwidth snapshot for adapting bitrate
    private boolean mDeleteAfterUploading;                              // Should recording files be deleted as they're uploaded?
    private ObjectMetadata mS3ManifestMeta;

    private ServerSocket serverSocket;
    private Socket uploadSocket;

    private boolean sending = false;


    /**
     * Construct a Broadcaster with Session settings and Kickflip credentials
     *
     * @param context       the host application {@link android.content.Context}.
     * @param config        the Session configuration. Specifies bitrates, resolution etc.
     */
    public Broadcaster(Context context, SessionConfig config) {
        super(config);
        //checkArgument(CLIENT_ID != null && CLIENT_SECRET != null);
        init();
        mContext = context;
        mConfig.getMuxer().setEventBus(mEventBus);
        mVideoBitrate = mConfig.getVideoBitrate();
        if (VERBOSE) Log.i(TAG, "Initial video bitrate : " + mVideoBitrate);
        mManifestSnapshotDir = new File(mConfig.getOutputPath().substring(0, mConfig.getOutputPath().lastIndexOf("/") + 1), "m3u8");
        mManifestSnapshotDir.mkdir();
        mVodManifest = new File(mManifestSnapshotDir, VOD_FILENAME);
        writeEventManifestHeader(mConfig.getHlsSegmentDuration());

        mReadyToBroadcast = false;
        Kickflip.setup(context);

    }

    private void init() {
        mDeleteAfterUploading = true;
        mLastRealizedBandwidthBytesPerSec = 0;
        mNumSegmentsWritten = 0;
        mSentBroadcastLiveEvent = false;
        mEventBus = new EventBus("Broadcaster");
        mEventBus.register(this);
    }

    /**
     * Set whether local recording files be deleted after successful upload. Default is true.
     * <p/>
     * Must be called before recording begins. Otherwise this method has no effect.
     *
     * @param doDelete whether local recording files be deleted after successful upload.
     */
    public void setDeleteLocalFilesAfterUpload(boolean doDelete) {
        if (!isRecording()) {
            mDeleteAfterUploading = doDelete;
        }
    }

    /**
     * Set a Listener to be notified of basic Broadcast events relevant to
     * updating a broadcasting UI.
     * e.g: Broadcast begun, went live, stopped, or encountered an error.
     * <p/>
     * See {@link io.kickflip.sdk.av.BroadcastListener}
     *
     * @param listener
     */
    public void setBroadcastListener(BroadcastListener listener) {
        mBroadcastListener = listener;
    }

    /**
     * Set an {@link com.google.common.eventbus.EventBus} to be notified
     * of events between {@link io.kickflip.sdk.av.Broadcaster},
     * {@link io.kickflip.sdk.av.HlsFileObserver}, {@link io.kickflip.sdk.api.s3.S3BroadcastManager}
     * e.g: A HLS MPEG-TS segment or .m3u8 Manifest was written to disk, or uploaded.
     * See a list of events in {@link io.kickflip.sdk.event}
     *
     * @return
     */
    public EventBus getEventBus() {
        return mEventBus;
    }

    /**
     * Start broadcasting.
     * <p/>
     * Must be called after {@link Broadcaster#setPreviewDisplay(io.kickflip.sdk.view.GLCameraView)}
     */
    @Override
    public void startRecording() {
        super.startRecording();

        sending = false;

        mUploadQueue = new LinkedBlockingQueue<File>();

        String watchDir = new File(mConfig.getOutputPath()).getParent();
        mFileObserver = new HlsFileObserver(watchDir, mEventBus);
        mFileObserver.startWatching();
        if (VERBOSE) Log.i(TAG, "Watching " + watchDir);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(8000);
                    uploadSocket = serverSocket.accept();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();

        onGotStreamResponse(new HlsStream());
        dequeueUploads();
    }

    private void onGotStreamResponse(HlsStream stream) {
        mStream = stream;
        if (mConfig.shouldAttachLocation()) {
            Kickflip.addLocationToStream(mContext, mStream, mEventBus);
        }
        mStream.setTitle(mConfig.getTitle());
        mStream.setDescription(mConfig.getDescription());
        mStream.setExtraInfo(mConfig.getExtraInfo());
        mStream.setIsPrivate(mConfig.isPrivate());
        if (VERBOSE) Log.i(TAG, "Got hls start stream " + stream);
        mReadyToBroadcast = true;
        mEventBus.post(new BroadcastIsBufferingEvent());
        if (mBroadcastListener != null) {
            mBroadcastListener.onBroadcastStart();
        }
    }

    /**
     * Check if the broadcast has gone live
     *
     * @return
     */
    public boolean isLive() {
        return mSentBroadcastLiveEvent;
    }

    /**
     * Stop broadcasting and release resources.
     * After this call this Broadcaster can no longer be used.
     */
    @Override
    public void stopRecording() {
        super.stopRecording();
        mFileObserver.stopWatching();
        try {
            if(uploadSocket != null && uploadSocket.isConnected()) {
                uploadSocket.close();
            }
            serverSocket.close();
            serverSocket = null;
            uploadSocket = null;
        } catch (IOException e) {
            e.printStackTrace();
        }

        mSentBroadcastLiveEvent = false;
        File currentDirectory = new File(mConfig.getOutputPath()).getParentFile();
        deleteFolder(currentDirectory);
    }

    private void deleteFolder(File folder) {
        for(File file : folder.listFiles()) {
            if(file.isDirectory()) {
                deleteFolder(file);
            } else {
                file.delete();
            }
        }
        folder.delete();
    }

    /**
     * A .ts file was written in the recording directory.
     * <p/>
     * Use this opportunity to verify the segment is of expected size
     * given the target bitrate
     * <p/>
     * Called on a background thread
     */
    @Subscribe
    public void onSegmentWritten(HlsSegmentWrittenEvent event) {
        try {
            if(!sending) {
                sending = true;
            }
            File hlsSegment = event.getSegment();
            if (isKitKat() && mConfig.isAdaptiveBitrate() && isRecording()) {
                // Adjust bitrate to match expected filesize
                long actualSegmentSizeBytes = hlsSegment.length();
                long expectedSizeBytes = ((mConfig.getAudioBitrate() / 8) + (mVideoBitrate / 8)) * mConfig.getHlsSegmentDuration();
                float filesizeRatio = actualSegmentSizeBytes / (float) expectedSizeBytes;
                if (VERBOSE)
                    Log.i(TAG, "OnSegmentWritten. Segment size: " + (actualSegmentSizeBytes / 1000) + "kB. expected: "+expectedSizeBytes+". ratio: " + filesizeRatio);
                if (filesizeRatio < .7) {
                    if (mLastRealizedBandwidthBytesPerSec != 0) {
                        // Scale bitrate while not exceeding available bandwidth
                        float scaledBitrate = mVideoBitrate * (1 / filesizeRatio);
                        float bandwidthBitrate = mLastRealizedBandwidthBytesPerSec * 8;
                        mVideoBitrate = (int) Math.min(scaledBitrate, bandwidthBitrate);
                    } else {
                        // Scale bitrate to match expected fileSize
                        mVideoBitrate *= (1 / filesizeRatio);
                    }
                    if (VERBOSE) Log.i(TAG, "Scaling video bitrate to " + mVideoBitrate + " bps");
                    adjustVideoBitrate(mVideoBitrate);
                }
            }
            queueOrSubmitUpload(hlsSegment);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * An S3 .ts segment upload completed.
     * <p/>
     * Use this opportunity to adjust bitrate based on the bandwidth
     * measured during this segment's transmission.
     * <p/>
     * Called on a background thread
     */
    private void onSegmentUploaded(UploadSegmentEvent uploadEvent) {
        if (mDeleteAfterUploading) {
            boolean deletedFile = uploadEvent.getFile().delete();
            if (VERBOSE)
                Log.i(TAG, "Deleting uploaded segment. " + uploadEvent.getFile().getAbsolutePath() + " Succcess: " + deletedFile);
        }
        try {
            if (isKitKat() && mConfig.isAdaptiveBitrate() && isRecording()) {
                mLastRealizedBandwidthBytesPerSec = uploadEvent.getUploadByteRate();
                // Adjust video encoder bitrate per bandwidth of just-completed upload
                if (VERBOSE) {
                    Log.i(TAG, "Bandwidth: " + (mLastRealizedBandwidthBytesPerSec / 1000.0) + " kBps. Encoder: " + ((mVideoBitrate + mConfig.getAudioBitrate()) / 8) / 1000.0 + " kBps");
                }
                if (mLastRealizedBandwidthBytesPerSec < (((mVideoBitrate + mConfig.getAudioBitrate()) / 8))) {
                    // The new bitrate is equal to the last upload bandwidth, never inferior to MIN_BITRATE, nor superior to the initial specified bitrate
                    mVideoBitrate = Math.max(Math.min(mLastRealizedBandwidthBytesPerSec * 8, mConfig.getVideoBitrate()), MIN_BITRATE);
                    if (VERBOSE) {
                        Log.i(TAG, String.format("Adjusting video bitrate to %f kBps. Bandwidth: %f kBps",
                                mVideoBitrate / (8 * 1000.0), mLastRealizedBandwidthBytesPerSec / 1000.0));
                    }
                    adjustVideoBitrate(mVideoBitrate);
                }
            }
        } catch (Exception e) {
            Log.i(TAG, "OnSegUpload excep");
            e.printStackTrace();
        }
    }

    /**
     * A .m3u8 file was written in the recording directory.
     * <p/>
     * Called on a background thread
     */
    @Subscribe
    public void onManifestUpdated(HlsManifestWrittenEvent e) {
        if (!isRecording()) {
            if (Kickflip.getBroadcastListener() != null) {
                if (VERBOSE) Log.i(TAG, "Sending onBroadcastStop");
                Kickflip.getBroadcastListener().onBroadcastStop();
            }
        }
        if (VERBOSE) Log.i(TAG, "onManifestUpdated. Last segment? " + !isRecording());
        // Copy m3u8 at this moment and queue it to uploading
        // service
        final File copy = new File(mManifestSnapshotDir, e.getManifestFile().getName()
                .replace(".m3u8", "_" + mNumSegmentsWritten + ".m3u8"));
        try {
            if (VERBOSE)
                Log.i(TAG, "Copying " + e.getManifestFile().getAbsolutePath() + " to " + copy.getAbsolutePath());
            FileUtils.copy(e.getManifestFile(), copy);
        } catch (IOException e1) {
            e1.printStackTrace();
        }
        queueOrSubmitUpload(copy);
        //appendLastManifestEntryToEventManifest(copy, !isRecording());
        mNumSegmentsWritten++;
    }

    /**
     * An S3 .m3u8 upload completed.
     * <p/>
     * Called on a background thread
     */
    private void onManifestUploaded(UploadSegmentEvent uploadEvent) {
        if (mDeleteAfterUploading) {
            if (VERBOSE) Log.i(TAG, "Deleting " + uploadEvent.getFile().getAbsolutePath());
            uploadEvent.getFile().delete();
            String uploadUrl = uploadEvent.getDestinationUrl();
            if (uploadUrl.substring(uploadUrl.lastIndexOf(File.separator) + 1).equals("vod.m3u8")) {
                if (VERBOSE) Log.i(TAG, "Deleting " + mConfig.getOutputDirectory());
                mFileObserver.stopWatching();
                FileUtils.deleteDirectory(mConfig.getOutputDirectory());
            }
        }
        if (!mSentBroadcastLiveEvent) {
            mEventBus.post(new BroadcastIsLiveEvent(((HlsStream) mStream).getKickflipUrl()));
            mSentBroadcastLiveEvent = true;
            if (mBroadcastListener != null)
                mBroadcastListener.onBroadcastLive(mStream);
        }
    }

    /**
     * A thumbnail was written in the recording directory.
     * <p/>
     * Called on a background thread
     */
    @Subscribe
    public void onThumbnailWritten(ThumbnailWrittenEvent e) {
        try {
            queueOrSubmitUpload(e.getThumbnailFile());
        } catch (Exception ex) {
            Log.i(TAG, "Error writing thumbanil");
            ex.printStackTrace();
        }
    }

    /**
     * A thumbnail upload completed.
     * <p/>
     * Called on a background thread
     */
    private void onThumbnailUploaded(UploadSegmentEvent uploadEvent) {
        if (mDeleteAfterUploading) uploadEvent.getFile().delete();
        if (mStream != null) {
            mStream.setThumbnailUrl(uploadEvent.getDestinationUrl());
            sendStreamMetaData();
        }
    }

    @Subscribe
    public void onStreamLocationAdded(StreamLocationAddedEvent event) {
        sendStreamMetaData();
    }

    @Subscribe
    public void onDeadEvent(DeadEvent e) {
        if (VERBOSE) Log.i(TAG, "DeadEvent ");
    }


    @Subscribe
    public void onMuxerFinished(MuxerFinishedEvent e) {
        // TODO: Broadcaster uses AVRecorder reset()
        // this seems better than nulling and recreating Broadcaster
        // since it should be usable as a static object for
        // bg recording
    }

    private void sendStreamMetaData() {
        if (mStream != null) {

        }
    }

    /**
     * Construct an S3 Key for a given filename
     *
     */
    private String keyForFilename(String fileName) {
        return mStream.getAwsS3Prefix() + fileName;
    }

    /**
     * Handle an upload, either submitting to the S3 client
     * or queueing for submission once credentials are ready
     *
     * @param file local file
     */
    private void queueOrSubmitUpload(File file) {
        queueUpload(file);
        if(mReadyToBroadcast && !mUploadQueue.isEmpty()) {
            submitUpload(mUploadQueue.poll());
        }
    }

    /**
     * Queue an upload for later submission to S3
     *
     * @param file local file
     */
    private void queueUpload(File file) {
        mUploadQueue.add(file);
    }

    private void submitUpload(final File file) {
        submitUpload(file, false);
    }

    private void submitUpload(final File file, boolean lastUpload) {
        // Valid ts file
        // TODO get real URL from webservice
        String url = "nothing here";
        getEventBus().post(new BroadcastIsLiveEvent(url));
        long startSending = System.currentTimeMillis();
        if(file.getName().endsWith(".ts")) {
            Log.d("BROADCASTER", "Sending file " + file.getName());
            mReadyToBroadcast = false;
            int bufferSize = 18800;
            int read = 0;
            long total = 0;
            try {
                FileInputStream fis = new FileInputStream(file);
                byte[] buffer = new byte[bufferSize];
                while ((read = fis.read(buffer)) > 0) {
                    if (read < bufferSize) {
                        read += fis.read(buffer, read, bufferSize - read);
                    }
                    total += read;
                    if (buffer[0] != 71) {
                        continue;
                    }

                    if(uploadSocket != null) {
                        synchronized (uploadSocket) {
                            if (!uploadSocket.isClosed())
                                uploadSocket.getOutputStream().write(buffer);
                        }
                    }
                }
                long timeElapsed = System.currentTimeMillis() - startSending;
                Log.d("BROADCASTER", "Finished sending file " + file.getName());
                int bps = (int) ((total*1000)/timeElapsed);
                onUploadComplete(new UploadSegmentEvent(file, ".ts", bps));
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mReadyToBroadcast = true;
            if(!mUploadQueue.isEmpty()) {
                Log.d("BROADCASTER", "Queue size: " +mUploadQueue.size() +", sending next file.");
                submitUpload(mUploadQueue.poll());
            }
        }



    }

    /**
     * An S3 Upload completed.
     * <p/>
     * Called on a background thread
     */
    public void onUploadComplete(UploadSegmentEvent uploadEvent) {
        if (VERBOSE) Log.i(TAG, "Upload completed for " + uploadEvent.getDestinationUrl());
        if (uploadEvent.getDestinationUrl().contains(".m3u8")) {
            onManifestUploaded(uploadEvent);
        } else if (uploadEvent.getDestinationUrl().contains(".ts")) {
            onSegmentUploaded(uploadEvent);
        } else if (uploadEvent.getDestinationUrl().contains(".jpg")) {
            onThumbnailUploaded(uploadEvent);
        }
    }

    public SessionConfig getSessionConfig() {
        return mConfig;
    }

    private void writeEventManifestHeader(int targetDuration) {
        FileUtils.writeStringToFile(
                String.format("#EXTM3U\n" +
                        "#EXT-X-PLAYLIST-TYPE:VOD\n" +
                        "#EXT-X-VERSION:3\n" +
                        "#EXT-X-MEDIA-SEQUENCE:0\n" +
                        "#EXT-X-TARGETDURATION:%d\n", targetDuration + 1),
                mVodManifest, false
        );
    }

    private void appendLastManifestEntryToEventManifest(File sourceManifest, boolean lastEntry) {
        String result = FileUtils.tail2(sourceManifest, lastEntry ? 3 : 2);
        FileUtils.writeStringToFile(result, mVodManifest, true);
        if (lastEntry) {
            submitUpload(mVodManifest, true);
            if (VERBOSE) Log.i(TAG, "Queued master manifest " + mVodManifest.getAbsolutePath());
        }
    }

    S3BroadcastManager.S3RequestInterceptor mS3RequestInterceptor = new S3BroadcastManager.S3RequestInterceptor() {
        @Override
        public void interceptRequest(PutObjectRequest request) {
            if (request.getKey().contains("index.m3u8")) {
                if (mS3ManifestMeta == null) {
                    mS3ManifestMeta = new ObjectMetadata();
                    mS3ManifestMeta.setCacheControl("max-age=0");
                }
                request.setMetadata(mS3ManifestMeta);
            }
        }
    };

    private void dequeueUploads() {
        if(mUploadQueue == null) {
            return;
        }

        for(File f : mUploadQueue) {
            submitUpload(f);
        }
    }

}
