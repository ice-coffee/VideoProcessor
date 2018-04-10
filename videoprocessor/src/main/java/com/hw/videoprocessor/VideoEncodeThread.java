package com.hw.videoprocessor;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;
import com.hw.videoprocessor.util.CL;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.hw.videoprocessor.VideoProcessor.DEFAULT_FRAME_RATE;
import static com.hw.videoprocessor.VideoProcessor.MIME_TYPE;
import static com.hw.videoprocessor.VideoProcessor.TIMEOUT_USEC;

/**
 * Created by huangwei on 2018/4/8 0008.
 */

public class VideoEncodeThread extends Thread implements IVideoEncodeThread {

    private MediaCodec mEncoder;
    private MediaMuxer mMuxer;
    private AtomicBoolean mDecodeDone;
    private CountDownLatch mMuxerStartLatch;
    private Exception mException;
    private int mBitrate;
    private int mResultWidth;
    private int mResultHeight;
    private int mIFrameInterval;
    private MediaExtractor mExtractor;
    private int mVideoIndex;
    //    private volatile InputSurface mInputSurface;
    private volatile CountDownLatch mEglContextLatch;
    private volatile Surface mSurface;


    public VideoEncodeThread(MediaExtractor extractor, MediaMuxer muxer,
                             int bitrate, int resultWidth, int resultHeight, int iFrameInterval,
                             int videoIndex,
                             AtomicBoolean decodeDone, CountDownLatch muxerStartLatch) {
        super("VideoProcessEncodeThread");
        mMuxer = muxer;
        mDecodeDone = decodeDone;
        mMuxerStartLatch = muxerStartLatch;
        mExtractor = extractor;
        mBitrate = bitrate;
        mResultHeight = resultHeight;
        mResultWidth = resultWidth;
        mIFrameInterval = iFrameInterval;
        mVideoIndex = videoIndex;
        mEglContextLatch = new CountDownLatch(1);
    }

    @Override
    public void run() {
        super.run();
        try {
            doEncode();
        } catch (Exception e) {
            CL.e(e);
            mException = e;
        } finally {
            if (mEncoder != null) {
                mEncoder.stop();
                mEncoder.release();
            }
        }
    }

    private void doEncode() throws IOException {
        MediaFormat inputFormat = mExtractor.getTrackFormat(mVideoIndex);
        //初始化编码器
        int frameRate = inputFormat.containsKey(MediaFormat.KEY_FRAME_RATE) ? inputFormat.getInteger(inputFormat.KEY_FRAME_RATE) : DEFAULT_FRAME_RATE;
        MediaFormat outputFormat = MediaFormat.createVideoFormat(MIME_TYPE, mResultWidth, mResultHeight);
        outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, mBitrate);
        outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, mIFrameInterval);

        mEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
        mEncoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mSurface = mEncoder.createInputSurface();

//        mInputSurface = new InputSurface(encodeSurface);
//        mInputSurface.makeCurrent();
        mEncoder.start();
        mEglContextLatch.countDown();

        boolean signalEncodeEnd = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int encodeTryAgainCount = 0;
        int videoTrackIndex = -5;
        //开始编码
        //输出
        while (true) {
            if (mDecodeDone.get() && !signalEncodeEnd) {
                signalEncodeEnd = true;
                mEncoder.signalEndOfInputStream();
            }
            int outputBufferIndex = mEncoder.dequeueOutputBuffer(info, TIMEOUT_USEC);
            CL.i("encode outputBufferIndex = " + outputBufferIndex);
            if (signalEncodeEnd && outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                encodeTryAgainCount++;
                if (encodeTryAgainCount > 10) {
                    //三星S8上出现signalEndOfInputStream之后一直tryAgain的问题
                    CL.e("INFO_TRY_AGAIN_LATER 10 times,force End!");
                    break;
                }
            } else {
                encodeTryAgainCount = 0;
            }
            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                continue;
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = mEncoder.getOutputFormat();
                if (videoTrackIndex == -5) {
                    videoTrackIndex = mMuxer.addTrack(newFormat);
                    mMuxer.start();
                    mMuxerStartLatch.countDown();
                }
                CL.i("encode newFormat = " + newFormat);
            } else if (outputBufferIndex < 0) {
                //ignore
                CL.e("unexpected result from decoder.dequeueOutputBuffer: " + outputBufferIndex);
            } else {
                //编码数据可用
                ByteBuffer outputBuffer = mEncoder.getOutputBuffer(outputBufferIndex);
                CL.i("writeSampleData,size:" + info.size + " time:" + info.presentationTimeUs / 1000);
                if (info.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM && info.presentationTimeUs < 0) {
                    info.presentationTimeUs = 0;
                }
                mMuxer.writeSampleData(videoTrackIndex, outputBuffer, info);

                mEncoder.releaseOutputBuffer(outputBufferIndex, false);
                if (info.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                    CL.i("encoderDone");
                    break;
                }
            }
        }
    }

    @Override
    public Surface getSurface() {
        return mSurface;
    }

    @Override
    public CountDownLatch getEglContextLatch() {
        return mEglContextLatch;
    }

    public Exception getException() {
        return mException;
    }
}