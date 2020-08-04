package com.hw.videoprocessor;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.view.Surface;
import com.hw.videoprocessor.util.CL;
import com.hw.videoprocessor.util.FrameDropper;
import com.hw.videoprocessor.util.InputSurface;
import com.hw.videoprocessor.util.OutputSurface;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.hw.videoprocessor.VideoProcessor.TIMEOUT_USEC;

/**
 * Created by huangwei on 2018/4/8 0008.
 */

public class VideoDecodeThread extends Thread {
    private MediaExtractor mExtractor;
    private MediaCodec mDecoder;
    private Integer mStartTimeMs;
    private Integer mEndTimeMs;
    private Float mSpeed;
    private AtomicBoolean mDecodeDone;
    private Exception mException;
    private int mVideoIndex;
    private IVideoEncodeThread mVideoEncodeThread;
    private InputSurface mInputSurface;
    private OutputSurface mOutputSurface;
    private Integer mDstFrameRate;
    private Integer mSrcFrameRate;
    private boolean mDropFrames;
    private FrameDropper mFrameDropper;

    public VideoDecodeThread(IVideoEncodeThread videoEncodeThread, MediaExtractor extractor,
                             @Nullable Integer startTimeMs, @Nullable Integer endTimeMs,
                             @Nullable Integer srcFrameRate, @Nullable Integer dstFrameRate, @Nullable Float speed,
                             boolean dropFrames,
                             int videoIndex, AtomicBoolean decodeDone

    ) {
        super("VideoProcessDecodeThread");
        mExtractor = extractor;
        mStartTimeMs = startTimeMs;
        mEndTimeMs = endTimeMs;
        mSpeed = speed;
        mVideoIndex = videoIndex;
        mDecodeDone = decodeDone;
        mVideoEncodeThread = videoEncodeThread;
        mDstFrameRate = dstFrameRate;
        mSrcFrameRate = srcFrameRate;
        mDropFrames = dropFrames;
    }

    @Override
    public void run() {
        super.run();
        try {
            doDecode();
        } catch (Exception e) {
            mException = e;
            CL.e(e);
        } finally {
            if (mInputSurface != null) {
                mInputSurface.release();
            }
            if (mOutputSurface != null) {
                mOutputSurface.release();
            }
            try {
                if (mDecoder != null) {
                    mDecoder.stop();
                    mDecoder.release();
                }
            } catch (Exception e) {
                mException = mException == null ? e : mException;
                CL.e(e);
            }
        }
    }

    private void doDecode() throws IOException {
        CountDownLatch eglContextLatch = mVideoEncodeThread.getEglContextLatch();
        try {
            boolean await = eglContextLatch.await(5, TimeUnit.SECONDS);
            if (!await) {
                mException = new TimeoutException("wait eglContext timeout!");
                return;
            }
        } catch (InterruptedException e) {
            CL.e(e);
            mException = e;
            return;
        }
        Surface encodeSurface = mVideoEncodeThread.getSurface();
        mInputSurface = new InputSurface(encodeSurface);
        mInputSurface.makeCurrent();

        MediaFormat inputFormat = mExtractor.getTrackFormat(mVideoIndex);

        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        CL.e(codecList.findDecoderForFormat(inputFormat));

        //初始化解码器
        mDecoder = MediaCodec.createByCodecName(codecList.findDecoderForFormat(inputFormat));
        mOutputSurface = new OutputSurface();
        mDecoder.configure(inputFormat, mOutputSurface.getSurface(), null, 0);
        mDecoder.start();
        //丢帧判断
        int frameIndex = 0;
        if (mDropFrames && mSrcFrameRate != null && mDstFrameRate != null) {
            if (mSpeed != null) {
                mSrcFrameRate = (int) (mSrcFrameRate * mSpeed);
            }
            if (mSrcFrameRate > mDstFrameRate) {
                mFrameDropper = new FrameDropper(mSrcFrameRate,mDstFrameRate);
                CL.w("帧率过高，需要丢帧:" + mSrcFrameRate + "->" + mDstFrameRate);
            }
        }
        //开始解码
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean decoderDone = false;
        boolean inputDone = false;
        long videoStartTimeUs = -1;
        int decodeTryAgainCount = 0;

        while (!decoderDone) {
            //还有帧数据，输入解码器
            if (!inputDone) {
                boolean eof = false;
                int index = mExtractor.getSampleTrackIndex();
                if (index == mVideoIndex) {
                    //返回可用的输入流缓冲区的索引，如果当前没有可用的输入缓冲区，则返回-1。
                    int inputBufIndex = mDecoder.dequeueInputBuffer(TIMEOUT_USEC);
                    if (inputBufIndex >= 0) {
                        //获取输入缓冲区ByteBuffer
                        ByteBuffer inputBuf = mDecoder.getInputBuffer(inputBufIndex);
                        //检索当前的数据，并从给定偏移量开始将其存储在字节缓冲区中。
                        int chunkSize = mExtractor.readSampleData(inputBuf, 0);
                        if (chunkSize < 0) {
                            //检索不到数据表明解码完成, 输入流入队end_of_stream标识
                            mDecoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            decoderDone = true;
                        } else {
                            //返回当前示例的显示时间(以微秒为单位)。
                            long sampleTime = mExtractor.getSampleTime();
                            //输入流入队,解码器解码
                            mDecoder.queueInputBuffer(inputBufIndex, 0, chunkSize, sampleTime, 0);
                            //读取下一帧数据
                            mExtractor.advance();
                        }
                    }
                } else if (index == -1) {
                    eof = true;
                }

                if (eof) {
                    //解码输入结束
                    CL.i("inputDone");
                    int inputBufIndex = mDecoder.dequeueInputBuffer(TIMEOUT_USEC);
                    if (inputBufIndex >= 0) {
                        mDecoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    }
                }
            }
            //解码器输出可用
            boolean decoderOutputAvailable = !decoderDone;
            if (decoderDone) {
                CL.i("decoderOutputAvailable:" + decoderOutputAvailable);
            }
            while (decoderOutputAvailable) {
                //从输出队列中取出解码之后的数据
                int outputBufferIndex = mDecoder.dequeueOutputBuffer(info, TIMEOUT_USEC);
                CL.i("outputBufferIndex = " + outputBufferIndex);
                if (inputDone && outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    decodeTryAgainCount++;
                    if (decodeTryAgainCount > 10) {
                        //小米2上出现BUFFER_FLAG_END_OF_STREAM之后一直tryAgain的问题
                        CL.e("INFO_TRY_AGAIN_LATER 10 times,force End!");
                        decoderDone = true;
                        break;
                    }
                } else {
                    decodeTryAgainCount = 0;
                }
                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break;
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = mDecoder.getOutputFormat();
                    CL.i("decode newFormat = " + newFormat);
                } else if (outputBufferIndex < 0) {
                    //ignore
                    CL.e("unexpected result from decoder.dequeueOutputBuffer: " + outputBufferIndex);
                } else {
                    boolean doRender = true;
                    //解码数据可用
                    if (mEndTimeMs != null && info.presentationTimeUs >= mEndTimeMs * 1000) {
                        inputDone = true;
                        decoderDone = true;
                        doRender = false;
                        info.flags |= MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                    }
                    if (mStartTimeMs != null && info.presentationTimeUs < mStartTimeMs * 1000) {
                        doRender = false;
                        CL.e("drop frame startTime = " + mStartTimeMs + " present time = " + info.presentationTimeUs / 1000);
                    }
                    if (info.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                        decoderDone = true;
                        mDecoder.releaseOutputBuffer(outputBufferIndex, false);
                        CL.i("decoderDone");
                        break;
                    }
                    //检查是否需要丢帧
                    if (mFrameDropper!=null && mFrameDropper.checkDrop(frameIndex)) {
                            CL.w("帧率过高，丢帧:" + frameIndex);
                            doRender = false;
                    }
                    frameIndex++;
                    //释放ByteBuffer缓冲区, 若解码器配置了surface,传入doRender = true则显示到surface上
                    mDecoder.releaseOutputBuffer(outputBufferIndex, doRender);
                    if (doRender) {
                        boolean errorWait = false;
                        try {
                            mOutputSurface.awaitNewImage();
                        } catch (Exception e) {
                            errorWait = true;
                            CL.e(e.getMessage());
                        }
                        if (!errorWait) {
                            if (videoStartTimeUs == -1) {
                                videoStartTimeUs = info.presentationTimeUs;
                                CL.i("videoStartTime:" + videoStartTimeUs / 1000);
                            }
                            mOutputSurface.drawImage(false);
                            long presentationTimeNs = (info.presentationTimeUs - videoStartTimeUs) * 1000;
                            if (mSpeed != null) {
                                presentationTimeNs /= mSpeed;
                            }
                            CL.i("drawImage,setPresentationTimeMs:" + presentationTimeNs / 1000 / 1000);
                            mInputSurface.setPresentationTime(presentationTimeNs);
                            mInputSurface.swapBuffers();
                            break;
                        }
                    }
                }
            }
        }
        CL.i("Video Decode Done!");
        mDecodeDone.set(true);
    }

    public Exception getException() {
        return mException;
    }
}