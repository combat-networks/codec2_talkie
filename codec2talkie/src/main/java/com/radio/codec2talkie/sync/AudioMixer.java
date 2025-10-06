package com.radio.codec2talkie.sync;

import android.util.Log;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * 音频混音器
 * 负责多路音频的混音处理
 */
public class AudioMixer {
    private static final String TAG = "AudioMixer";
    
    // 混音参数
    private static final int DEFAULT_BUFFER_SIZE = 160;        // 默认缓冲区大小（20ms @ 8kHz）
    private static final int MAX_CHANNELS = 8;                 // 最大通道数
    private static final float DEFAULT_GAIN = 1.0f;           // 默认增益
    private static final float MAX_GAIN = 2.0f;                // 最大增益
    private static final float MIN_GAIN = 0.0f;                // 最小增益
    
    // 混音状态
    private final AtomicBoolean isMixingActive = new AtomicBoolean(false);
    private final Object mixingLock = new Object();            // 混音锁
    
    // 通道管理
    private final Map<String, MixingChannel> channels = new ConcurrentHashMap<>();
    private final Map<String, Float> channelGains = new ConcurrentHashMap<>();
    
    // 混音缓冲区
    private final short[] mixingBuffer;
    private final short[] outputBuffer;
    
    // 混音统计
    private volatile long totalMixingOperations = 0;
    private volatile long totalOutputSamples = 0;
    private volatile int mixingErrors = 0;
    
    public AudioMixer() {
        this(DEFAULT_BUFFER_SIZE);
    }
    
    public AudioMixer(int bufferSize) {
        this.mixingBuffer = new short[bufferSize];
        this.outputBuffer = new short[bufferSize];
        
        Log.i(TAG, "AudioMixer initialized with buffer size: " + bufferSize);
    }
    
    /**
     * 初始化混音器
     */
    public void initialize() {
        synchronized (mixingLock) {
            if (isMixingActive.get()) {
                Log.w(TAG, "Audio mixer already initialized");
                return;
            }
            
            isMixingActive.set(true);
            Log.i(TAG, "Audio mixer initialized");
        }
    }
    
    /**
     * 添加混音通道
     */
    public boolean addChannel(String channelId) {
        if (!isMixingActive.get()) {
            Log.e(TAG, "Audio mixer not initialized");
            return false;
        }
        
        if (channels.size() >= MAX_CHANNELS) {
            Log.e(TAG, "Maximum number of channels reached: " + MAX_CHANNELS);
            return false;
        }
        
        synchronized (mixingLock) {
            if (channels.containsKey(channelId)) {
                Log.w(TAG, "Channel already exists: " + channelId);
                return false;
            }
            
            channels.put(channelId, new MixingChannel(channelId));
            channelGains.put(channelId, DEFAULT_GAIN);
            
            Log.i(TAG, "Added mixing channel: " + channelId);
            return true;
        }
    }
    
    /**
     * 移除混音通道
     */
    public boolean removeChannel(String channelId) {
        synchronized (mixingLock) {
            MixingChannel channel = channels.remove(channelId);
            if (channel == null) {
                Log.w(TAG, "Channel not found: " + channelId);
                return false;
            }
            
            channelGains.remove(channelId);
            Log.i(TAG, "Removed mixing channel: " + channelId);
            return true;
        }
    }
    
    /**
     * 设置通道增益
     */
    public boolean setChannelGain(String channelId, float gain) {
        if (gain < MIN_GAIN || gain > MAX_GAIN) {
            Log.e(TAG, "Invalid gain value: " + gain);
            return false;
        }
        
        synchronized (mixingLock) {
            if (!channels.containsKey(channelId)) {
                Log.e(TAG, "Channel not found: " + channelId);
                return false;
            }
            
            channelGains.put(channelId, gain);
            Log.i(TAG, "Set gain for channel " + channelId + ": " + gain);
            return true;
        }
    }
    
    /**
     * 获取通道增益
     */
    public float getChannelGain(String channelId) {
        synchronized (mixingLock) {
            return channelGains.getOrDefault(channelId, DEFAULT_GAIN);
        }
    }
    
    /**
     * 添加音频帧到混音器
     */
    public boolean addAudioFrame(String channelId, AudioFrame frame) {
        if (!isMixingActive.get()) {
            Log.w(TAG, "Audio mixer not active");
            return false;
        }
        
        MixingChannel channel = channels.get(channelId);
        if (channel == null) {
            Log.e(TAG, "Channel not found: " + channelId);
            return false;
        }
        
        try {
            // 应用通道增益
            float gain = getChannelGain(channelId);
            AudioFrame gainedFrame = frame.applyGain(gain);
            
            // 添加到通道缓冲区
            boolean added = channel.addFrame(gainedFrame);
            if (added) {
                Log.d(TAG, "Added frame to channel " + channelId + " mixer");
            } else {
                Log.w(TAG, "Failed to add frame to channel " + channelId + " mixer");
            }
            
            return added;
        } catch (Exception e) {
            mixingErrors++;
            Log.e(TAG, "Error adding frame to channel " + channelId, e);
            return false;
        }
    }
    
    /**
     * 获取混音后的音频数据
     */
    public short[] getMixedAudio() {
        if (!isMixingActive.get()) {
            Log.w(TAG, "Audio mixer not active");
            return null;
        }
        
        synchronized (mixingLock) {
            try {
                // 清空混音缓冲区
                Arrays.fill(mixingBuffer, (short) 0);
                
                // 混音所有通道
                boolean hasAudio = false;
                for (MixingChannel channel : channels.values()) {
                    AudioFrame frame = channel.getNextFrame();
                    if (frame != null && frame.isValid()) {
                        mixChannelAudio(frame);
                        hasAudio = true;
                    }
                }
                
                if (hasAudio) {
                    // 应用混音限制
                    applyMixingLimits();
                    
                    // 复制到输出缓冲区
                    System.arraycopy(mixingBuffer, 0, outputBuffer, 0, mixingBuffer.length);
                    
                    totalMixingOperations++;
                    totalOutputSamples += mixingBuffer.length;
                    
                    Log.d(TAG, "Mixed audio generated, samples: " + mixingBuffer.length);
                    return outputBuffer.clone();
                } else {
                    Log.d(TAG, "No audio data to mix");
                    return null;
                }
            } catch (Exception e) {
                mixingErrors++;
                Log.e(TAG, "Error in audio mixing", e);
                return null;
            }
        }
    }
    
    /**
     * 混音单个通道的音频
     */
    private void mixChannelAudio(AudioFrame frame) {
        short[] audioData = frame.getAudioDataDirect();
        int minLength = Math.min(mixingBuffer.length, audioData.length);
        
        for (int i = 0; i < minLength; i++) {
            // 简单的加法混音
            int mixed = mixingBuffer[i] + audioData[i];
            
            // 防止溢出
            if (mixed > Short.MAX_VALUE) {
                mixed = Short.MAX_VALUE;
            } else if (mixed < Short.MIN_VALUE) {
                mixed = Short.MIN_VALUE;
            }
            
            mixingBuffer[i] = (short) mixed;
        }
    }
    
    /**
     * 应用混音限制
     */
    private void applyMixingLimits() {
        // 简单的限制器，防止削波
        for (int i = 0; i < mixingBuffer.length; i++) {
            short sample = mixingBuffer[i];
            
            // 软限制
            if (Math.abs(sample) > Short.MAX_VALUE * 0.8f) {
                float factor = (Short.MAX_VALUE * 0.8f) / Math.abs(sample);
                mixingBuffer[i] = (short) (sample * factor);
            }
        }
    }
    
    /**
     * 获取混音统计信息
     */
    public MixingStatistics getMixingStatistics() {
        MixingStatistics stats = new MixingStatistics();
        stats.totalMixingOperations = totalMixingOperations;
        stats.totalOutputSamples = totalOutputSamples;
        stats.mixingErrors = mixingErrors;
        stats.activeChannels = channels.size();
        
        return stats;
    }
    
    /**
     * 重置混音统计
     */
    public void resetMixingStatistics() {
        synchronized (mixingLock) {
            totalMixingOperations = 0;
            totalOutputSamples = 0;
            mixingErrors = 0;
            Log.i(TAG, "Mixing statistics reset");
        }
    }
    
    /**
     * 清空所有通道缓冲区
     */
    public void clearAllChannels() {
        synchronized (mixingLock) {
            for (MixingChannel channel : channels.values()) {
                channel.clear();
            }
            Log.i(TAG, "Cleared all channel buffers");
        }
    }
    
    /**
     * 清空指定通道缓冲区
     */
    public void clearChannel(String channelId) {
        synchronized (mixingLock) {
            MixingChannel channel = channels.get(channelId);
            if (channel != null) {
                channel.clear();
                Log.i(TAG, "Cleared channel buffer: " + channelId);
            }
        }
    }
    
    /**
     * 关闭混音器
     */
    public void shutdown() {
        synchronized (mixingLock) {
            if (!isMixingActive.get()) {
                Log.w(TAG, "Audio mixer already stopped");
                return;
            }
            
            isMixingActive.set(false);
            
            // 清空所有通道
            clearAllChannels();
            channels.clear();
            channelGains.clear();
            
            Log.i(TAG, "Audio mixer shutdown completed");
        }
    }
    
    /**
     * 混音通道
     */
    private static class MixingChannel {
        private final String channelId;
        private final List<AudioFrame> frameBuffer;
        private int currentFrameIndex;
        
        public MixingChannel(String channelId) {
            this.channelId = channelId;
            this.frameBuffer = new ArrayList<>();
            this.currentFrameIndex = 0;
        }
        
        public boolean addFrame(AudioFrame frame) {
            if (frame == null || !frame.isValid()) {
                return false;
            }
            
            frameBuffer.add(frame);
            
            // 限制缓冲区大小
            if (frameBuffer.size() > 10) {
                frameBuffer.remove(0);
            }
            
            return true;
        }
        
        public AudioFrame getNextFrame() {
            if (currentFrameIndex >= frameBuffer.size()) {
                return null;
            }
            
            AudioFrame frame = frameBuffer.get(currentFrameIndex);
            currentFrameIndex++;
            
            return frame;
        }
        
        public void clear() {
            frameBuffer.clear();
            currentFrameIndex = 0;
        }
    }
    
    /**
     * 混音统计信息
     */
    public static class MixingStatistics {
        public long totalMixingOperations;
        public long totalOutputSamples;
        public int mixingErrors;
        public int activeChannels;
        
        @Override
        public String toString() {
            return String.format("MixingStats[ops=%d, samples=%d, errors=%d, channels=%d]", 
                    totalMixingOperations, totalOutputSamples, mixingErrors, activeChannels);
        }
    }
}
