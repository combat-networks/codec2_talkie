package com.radio.codec2talkie.sync;

import android.util.Log;
import java.util.Arrays;

/**
 * 音频帧数据结构
 * 用于多路音频同步处理
 */
public class AudioFrame {
    private static final String TAG = "AudioFrame";
    
    private final String channelId;           // 通道ID
    private final short[] audioData;         // 音频数据
    private final long timestamp;            // 时间戳
    private final int sampleCount;           // 采样数
    private final long sequenceNumber;       // 序列号
    private final int sampleRate;           // 采样率
    private final int channels;             // 声道数
    
    private static long globalSequenceNumber = 0;
    
    /**
     * 构造函数
     */
    public AudioFrame(String channelId, short[] audioData, long timestamp, int sampleCount) {
        this(channelId, audioData, timestamp, sampleCount, 8000, 1);
    }
    
    /**
     * 完整构造函数
     */
    public AudioFrame(String channelId, short[] audioData, long timestamp, int sampleCount, 
                     int sampleRate, int channels) {
        this.channelId = channelId;
        this.audioData = audioData.clone(); // 深拷贝
        this.timestamp = timestamp;
        this.sampleCount = sampleCount;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.sequenceNumber = ++globalSequenceNumber;
        
        Log.d(TAG, "Created AudioFrame: " + toString());
    }
    
    /**
     * 获取通道ID
     */
    public String getChannelId() {
        return channelId;
    }
    
    /**
     * 获取音频数据（深拷贝）
     */
    public short[] getAudioData() {
        return audioData.clone();
    }
    
    /**
     * 获取音频数据（直接引用，注意线程安全）
     */
    public short[] getAudioDataDirect() {
        return audioData;
    }
    
    /**
     * 获取时间戳
     */
    public long getTimestamp() {
        return timestamp;
    }
    
    /**
     * 获取采样数
     */
    public int getSampleCount() {
        return sampleCount;
    }
    
    /**
     * 获取序列号
     */
    public long getSequenceNumber() {
        return sequenceNumber;
    }
    
    /**
     * 获取采样率
     */
    public int getSampleRate() {
        return sampleRate;
    }
    
    /**
     * 获取声道数
     */
    public int getChannels() {
        return channels;
    }
    
    /**
     * 获取音频数据长度
     */
    public int getDataLength() {
        return audioData.length;
    }
    
    /**
     * 获取音频持续时间（毫秒）
     */
    public long getDurationMs() {
        return (long) ((double) sampleCount / sampleRate * 1000);
    }
    
    /**
     * 检查音频帧是否有效
     */
    public boolean isValid() {
        return channelId != null && 
               audioData != null && 
               audioData.length > 0 && 
               sampleCount > 0 && 
               sampleRate > 0 && 
               channels > 0;
    }
    
    /**
     * 检查音频帧是否为空（静音）
     */
    public boolean isSilent() {
        if (audioData == null || audioData.length == 0) {
            return true;
        }
        
        // 检查是否所有样本都接近零
        for (short sample : audioData) {
            if (Math.abs(sample) > 100) { // 阈值可调整
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * 获取音频帧的RMS值（均方根）
     */
    public double getRms() {
        if (audioData == null || audioData.length == 0) {
            return 0.0;
        }
        
        long sum = 0;
        for (short sample : audioData) {
            sum += (long) sample * sample;
        }
        
        return Math.sqrt((double) sum / audioData.length);
    }
    
    /**
     * 获取音频帧的峰值
     */
    public short getPeak() {
        if (audioData == null || audioData.length == 0) {
            return 0;
        }
        
        short peak = 0;
        for (short sample : audioData) {
            peak = (short) Math.max(peak, Math.abs(sample));
        }
        
        return peak;
    }
    
    /**
     * 应用音量增益
     */
    public AudioFrame applyGain(float gain) {
        if (gain == 1.0f) {
            return this; // 无需处理
        }
        
        short[] newAudioData = new short[audioData.length];
        for (int i = 0; i < audioData.length; i++) {
            int sample = (int) (audioData[i] * gain);
            // 防止溢出
            sample = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample));
            newAudioData[i] = (short) sample;
        }
        
        return new AudioFrame(channelId, newAudioData, timestamp, sampleCount, sampleRate, channels);
    }
    
    /**
     * 应用淡入效果
     */
    public AudioFrame applyFadeIn(int fadeSamples) {
        if (fadeSamples <= 0 || fadeSamples >= audioData.length) {
            return this;
        }
        
        short[] newAudioData = audioData.clone();
        for (int i = 0; i < fadeSamples; i++) {
            float factor = (float) i / fadeSamples;
            newAudioData[i] = (short) (audioData[i] * factor);
        }
        
        return new AudioFrame(channelId, newAudioData, timestamp, sampleCount, sampleRate, channels);
    }
    
    /**
     * 应用淡出效果
     */
    public AudioFrame applyFadeOut(int fadeSamples) {
        if (fadeSamples <= 0 || fadeSamples >= audioData.length) {
            return this;
        }
        
        short[] newAudioData = audioData.clone();
        int startIndex = audioData.length - fadeSamples;
        for (int i = 0; i < fadeSamples; i++) {
            float factor = 1.0f - (float) i / fadeSamples;
            newAudioData[startIndex + i] = (short) (audioData[startIndex + i] * factor);
        }
        
        return new AudioFrame(channelId, newAudioData, timestamp, sampleCount, sampleRate, channels);
    }
    
    /**
     * 截取音频帧的一部分
     */
    public AudioFrame subFrame(int startSample, int length) {
        if (startSample < 0 || length <= 0 || startSample + length > audioData.length) {
            throw new IllegalArgumentException("Invalid subframe parameters");
        }
        
        short[] subData = Arrays.copyOfRange(audioData, startSample, startSample + length);
        return new AudioFrame(channelId, subData, timestamp, length, sampleRate, channels);
    }
    
    /**
     * 合并两个音频帧
     */
    public static AudioFrame merge(AudioFrame frame1, AudioFrame frame2) {
        if (frame1 == null || frame2 == null) {
            return frame1 != null ? frame1 : frame2;
        }
        
        if (!frame1.channelId.equals(frame2.channelId)) {
            throw new IllegalArgumentException("Cannot merge frames from different channels");
        }
        
        if (frame1.sampleRate != frame2.sampleRate || frame1.channels != frame2.channels) {
            throw new IllegalArgumentException("Cannot merge frames with different audio properties");
        }
        
        short[] mergedData = new short[frame1.audioData.length + frame2.audioData.length];
        System.arraycopy(frame1.audioData, 0, mergedData, 0, frame1.audioData.length);
        System.arraycopy(frame2.audioData, 0, mergedData, frame1.audioData.length, frame2.audioData.length);
        
        return new AudioFrame(frame1.channelId, mergedData, frame1.timestamp, 
                            frame1.sampleCount + frame2.sampleCount, 
                            frame1.sampleRate, frame1.channels);
    }
    
    /**
     * 创建静音音频帧
     */
    public static AudioFrame createSilent(String channelId, int sampleCount, int sampleRate, int channels) {
        short[] silentData = new short[sampleCount];
        Arrays.fill(silentData, (short) 0);
        
        return new AudioFrame(channelId, silentData, System.currentTimeMillis(), 
                            sampleCount, sampleRate, channels);
    }
    
    /**
     * 创建静音音频帧（使用默认参数）
     */
    public static AudioFrame createSilent(String channelId, int sampleCount) {
        return createSilent(channelId, sampleCount, 8000, 1);
    }
    
    @Override
    public String toString() {
        return String.format("AudioFrame[channel=%s, samples=%d, timestamp=%d, seq=%d, rate=%d, ch=%d]", 
                channelId, sampleCount, timestamp, sequenceNumber, sampleRate, channels);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        AudioFrame that = (AudioFrame) obj;
        return sequenceNumber == that.sequenceNumber;
    }
    
    @Override
    public int hashCode() {
        return Long.hashCode(sequenceNumber);
    }
}
