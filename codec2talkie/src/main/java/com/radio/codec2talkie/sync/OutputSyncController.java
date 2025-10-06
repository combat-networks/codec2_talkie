package com.radio.codec2talkie.sync;

import android.media.AudioTrack;
import android.util.Log;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Timer;
import java.util.TimerTask;

/**
 * 输出同步控制器
 * 负责管理多路音频的混音输出同步
 */
public class OutputSyncController {
    private static final String TAG = "OutputSyncController";
    
    // 输出同步参数
    private static final int OUTPUT_BUFFER_SIZE = 2048;         // 输出缓冲区大小
    private static final int MIXING_INTERVAL_MS = 10;           // 混音间隔10ms
    private static final int MAX_OUTPUT_LATENCY_MS = 50;      // 最大输出延迟50ms
    private static final int MIN_OUTPUT_BUFFER_SIZE = 512;     // 最小输出缓冲区大小
    
    // 同步状态
    private final AtomicBoolean isOutputSyncActive = new AtomicBoolean(false);
    private final Object outputSyncLock = new Object();        // 输出同步锁
    
    // 混音输出组件
    private final AudioMixer audioMixer;
    private final AudioTrack outputAudioTrack;
    private final Timer outputTimer;
    
    // 输出统计
    private volatile long totalBytesWritten = 0;
    private volatile int outputErrors = 0;
    private volatile long lastOutputTime = 0;
    
    public OutputSyncController(AudioMixer mixer, AudioTrack audioTrack) {
        this.audioMixer = mixer;
        this.outputAudioTrack = audioTrack;
        this.outputTimer = new Timer("OutputSyncTimer");
        
        Log.i(TAG, "OutputSyncController initialized");
    }
    
    /**
     * 初始化输出同步
     */
    public void initializeOutputSync() {
        synchronized (outputSyncLock) {
            if (isOutputSyncActive.get()) {
                Log.w(TAG, "Output sync already initialized");
                return;
            }
            
            isOutputSyncActive.set(true);
            
            // 启动输出定时器
            startOutputTimer();
            
            Log.i(TAG, "Output sync initialized");
        }
    }
    
    /**
     * 启动输出定时器
     */
    private void startOutputTimer() {
        outputTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                processOutputSync();
            }
        }, MIXING_INTERVAL_MS, MIXING_INTERVAL_MS);
    }
    
    /**
     * 处理输出同步
     */
    private void processOutputSync() {
        if (!isOutputSyncActive.get()) {
            return;
        }
        
        try {
            // 获取混音后的音频数据
            short[] mixedAudio = audioMixer.getMixedAudio();
            if (mixedAudio != null && mixedAudio.length > 0) {
                // 写入AudioTrack
                int bytesWritten = writeToAudioTrack(mixedAudio);
                if (bytesWritten > 0) {
                    totalBytesWritten += bytesWritten;
                    lastOutputTime = System.currentTimeMillis();
                    Log.d(TAG, "Wrote " + bytesWritten + " bytes to AudioTrack");
                } else {
                    outputErrors++;
                    Log.w(TAG, "Failed to write audio to AudioTrack");
                }
            } else {
                Log.d(TAG, "No mixed audio data available");
            }
        } catch (Exception e) {
            outputErrors++;
            Log.e(TAG, "Error in output sync processing", e);
        }
    }
    
    /**
     * 写入音频数据到AudioTrack
     */
    private int writeToAudioTrack(short[] audioData) {
        if (outputAudioTrack == null) {
            Log.e(TAG, "AudioTrack not initialized");
            return 0;
        }
        
        try {
            // 检查AudioTrack状态
            if (outputAudioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                Log.w(TAG, "AudioTrack not playing, attempting to start");
                outputAudioTrack.play();
            }
            
            // 写入音频数据
            int bytesWritten = outputAudioTrack.write(audioData, 0, audioData.length);
            
            if (bytesWritten < 0) {
                Log.e(TAG, "AudioTrack write error: " + bytesWritten);
                return 0;
            }
            
            return bytesWritten;
        } catch (Exception e) {
            Log.e(TAG, "Error writing to AudioTrack", e);
            return 0;
        }
    }
    
    /**
     * 强制输出音频数据
     */
    public void forceOutput(short[] audioData) {
        if (!isOutputSyncActive.get()) {
            Log.w(TAG, "Output sync not active");
            return;
        }
        
        if (audioData == null || audioData.length == 0) {
            Log.w(TAG, "No audio data to output");
            return;
        }
        
        try {
            int bytesWritten = writeToAudioTrack(audioData);
            if (bytesWritten > 0) {
                totalBytesWritten += bytesWritten;
                lastOutputTime = System.currentTimeMillis();
                Log.d(TAG, "Force output " + bytesWritten + " bytes");
            }
        } catch (Exception e) {
            outputErrors++;
            Log.e(TAG, "Error in force output", e);
        }
    }
    
    /**
     * 检查输出延迟
     */
    public boolean isOutputLatencyAcceptable() {
        if (lastOutputTime == 0) {
            return true; // 还没有输出过
        }
        
        long currentTime = System.currentTimeMillis();
        long latency = currentTime - lastOutputTime;
        
        return latency <= MAX_OUTPUT_LATENCY_MS;
    }
    
    /**
     * 获取输出缓冲区状态
     */
    public OutputBufferStatus getOutputBufferStatus() {
        if (outputAudioTrack == null) {
            return new OutputBufferStatus(0, 0, false);
        }
        
        try {
            int bufferSize = outputAudioTrack.getBufferSizeInFrames();
            int bufferSizeInBytes = bufferSize * 2; // 16-bit samples
            int playState = outputAudioTrack.getPlayState();
            boolean isPlaying = (playState == AudioTrack.PLAYSTATE_PLAYING);
            
            return new OutputBufferStatus(bufferSize, bufferSizeInBytes, isPlaying);
        } catch (Exception e) {
            Log.e(TAG, "Error getting output buffer status", e);
            return new OutputBufferStatus(0, 0, false);
        }
    }
    
    /**
     * 调整输出缓冲区大小
     */
    public boolean adjustOutputBufferSize(int newSize) {
        if (newSize < MIN_OUTPUT_BUFFER_SIZE) {
            Log.w(TAG, "Output buffer size too small: " + newSize);
            return false;
        }
        
        synchronized (outputSyncLock) {
            try {
                // 这里可以实现动态调整缓冲区大小的逻辑
                Log.i(TAG, "Output buffer size adjusted to: " + newSize);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Error adjusting output buffer size", e);
                return false;
            }
        }
    }
    
    /**
     * 暂停输出
     */
    public void pauseOutput() {
        synchronized (outputSyncLock) {
            if (outputAudioTrack != null && outputAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                outputAudioTrack.pause();
                Log.i(TAG, "Output paused");
            }
        }
    }
    
    /**
     * 恢复输出
     */
    public void resumeOutput() {
        synchronized (outputSyncLock) {
            if (outputAudioTrack != null && outputAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_PAUSED) {
                outputAudioTrack.play();
                Log.i(TAG, "Output resumed");
            }
        }
    }
    
    /**
     * 停止输出
     */
    public void stopOutput() {
        synchronized (outputSyncLock) {
            if (outputAudioTrack != null && outputAudioTrack.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) {
                outputAudioTrack.stop();
                Log.i(TAG, "Output stopped");
            }
        }
    }
    
    /**
     * 获取输出统计信息
     */
    public OutputStatistics getOutputStatistics() {
        OutputStatistics stats = new OutputStatistics();
        stats.totalBytesWritten = totalBytesWritten;
        stats.outputErrors = outputErrors;
        stats.lastOutputTime = lastOutputTime;
        stats.isLatencyAcceptable = isOutputLatencyAcceptable();
        
        return stats;
    }
    
    /**
     * 重置输出统计
     */
    public void resetOutputStatistics() {
        synchronized (outputSyncLock) {
            totalBytesWritten = 0;
            outputErrors = 0;
            lastOutputTime = 0;
            Log.i(TAG, "Output statistics reset");
        }
    }
    
    /**
     * 停止输出同步
     */
    public void shutdown() {
        synchronized (outputSyncLock) {
            if (!isOutputSyncActive.get()) {
                Log.w(TAG, "Output sync already stopped");
                return;
            }
            
            isOutputSyncActive.set(false);
            
            if (outputTimer != null) {
                outputTimer.cancel();
            }
            
            // 停止AudioTrack
            if (outputAudioTrack != null) {
                try {
                    outputAudioTrack.stop();
                    outputAudioTrack.flush();
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping AudioTrack", e);
                }
            }
            
            Log.i(TAG, "Output sync shutdown completed");
        }
    }
    
    /**
     * 输出缓冲区状态
     */
    public static class OutputBufferStatus {
        public final int bufferSizeFrames;
        public final int bufferSizeBytes;
        public final boolean isPlaying;
        
        public OutputBufferStatus(int bufferSizeFrames, int bufferSizeBytes, boolean isPlaying) {
            this.bufferSizeFrames = bufferSizeFrames;
            this.bufferSizeBytes = bufferSizeBytes;
            this.isPlaying = isPlaying;
        }
        
        @Override
        public String toString() {
            return String.format("OutputBufferStatus[frames=%d, bytes=%d, playing=%s]", 
                    bufferSizeFrames, bufferSizeBytes, isPlaying);
        }
    }
    
    /**
     * 输出统计信息
     */
    public static class OutputStatistics {
        public long totalBytesWritten;
        public int outputErrors;
        public long lastOutputTime;
        public boolean isLatencyAcceptable;
        
        @Override
        public String toString() {
            return String.format("OutputStats[bytes=%d, errors=%d, lastTime=%d, latencyOk=%s]", 
                    totalBytesWritten, outputErrors, lastOutputTime, isLatencyAcceptable);
        }
    }
}
