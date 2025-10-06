package com.radio.codec2talkie.sync;

import android.util.Log;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;

/**
 * 音频同步管理器
 * 负责协调多路音频解码的同步处理
 */
public class AudioSyncManager {
    private static final String TAG = "AudioSyncManager";
    
    // 同步参数
    private static final long SYNC_INTERVAL_MS = 10;           // 10ms同步间隔
    private static final long MAX_SYNC_DRIFT_MS = 5;           // 最大同步漂移5ms
    private static final int MAX_CHANNELS = 8;                  // 最大通道数
    
    // 同步状态
    private final AtomicBoolean isSyncActive = new AtomicBoolean(false);
    private final Map<String, ChannelSyncState> channelStates = new ConcurrentHashMap<>();
    
    // 同步控制器
    private final TimeSyncController timeSyncController;
    private final DataSyncController dataSyncController;
    private final OutputSyncController outputSyncController;
    
    public AudioSyncManager() {
        this.timeSyncController = new TimeSyncController();
        this.dataSyncController = new DataSyncController();
        this.outputSyncController = new OutputSyncController();
        
        Log.i(TAG, "AudioSyncManager initialized");
    }
    
    /**
     * 初始化同步管理器
     */
    public void initialize() {
        synchronized (this) {
            if (isSyncActive.get()) {
                Log.w(TAG, "Sync manager already initialized");
                return;
            }
            
            // 初始化各个同步控制器
            timeSyncController.initializeSync();
            dataSyncController.initializeDataSync();
            outputSyncController.initializeOutputSync();
            
            isSyncActive.set(true);
            Log.i(TAG, "AudioSyncManager initialized successfully");
        }
    }
    
    /**
     * 注册音频通道
     */
    public boolean registerChannel(String channelId) {
        if (!isSyncActive.get()) {
            Log.e(TAG, "Sync manager not initialized");
            return false;
        }
        
        if (channelStates.size() >= MAX_CHANNELS) {
            Log.e(TAG, "Maximum number of channels reached: " + MAX_CHANNELS);
            return false;
        }
        
        synchronized (this) {
            if (channelStates.containsKey(channelId)) {
                Log.w(TAG, "Channel already registered: " + channelId);
                return false;
            }
            
            // 创建通道同步状态
            ChannelSyncState channelState = new ChannelSyncState(channelId);
            channelStates.put(channelId, channelState);
            
            // 注册到各个控制器
            dataSyncController.registerChannelBuffer(channelId);
            
            Log.i(TAG, "Registered channel: " + channelId);
            return true;
        }
    }
    
    /**
     * 注销音频通道
     */
    public boolean unregisterChannel(String channelId) {
        synchronized (this) {
            ChannelSyncState channelState = channelStates.remove(channelId);
            if (channelState == null) {
                Log.w(TAG, "Channel not found: " + channelId);
                return false;
            }
            
            // 清理通道状态
            channelState.cleanup();
            
            Log.i(TAG, "Unregistered channel: " + channelId);
            return true;
        }
    }
    
    /**
     * 处理音频帧同步
     */
    public void processAudioFrame(String channelId, byte[] encodedFrame) {
        if (!isSyncActive.get()) {
            Log.w(TAG, "Sync manager not active");
            return;
        }
        
        ChannelSyncState channelState = channelStates.get(channelId);
        if (channelState == null) {
            Log.e(TAG, "Channel not registered: " + channelId);
            return;
        }
        
        try {
            // 更新通道状态
            channelState.updateLastActivity();
            
            // 注册时间戳
            long currentTimestamp = System.currentTimeMillis();
            timeSyncController.registerChannelTimestamp(channelId, currentTimestamp);
            
            // 处理音频帧
            channelState.processAudioFrame(encodedFrame);
            
            Log.d(TAG, "Processed audio frame for channel: " + channelId);
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing audio frame for channel: " + channelId, e);
        }
    }
    
    /**
     * 获取同步状态
     */
    public boolean isChannelSynced(String channelId) {
        ChannelSyncState channelState = channelStates.get(channelId);
        if (channelState == null) {
            return false;
        }
        
        return channelState.isSynced();
    }
    
    /**
     * 获取所有通道同步状态
     */
    public boolean areAllChannelsSynced() {
        if (channelStates.isEmpty()) {
            return false;
        }
        
        for (ChannelSyncState channelState : channelStates.values()) {
            if (!channelState.isSynced()) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * 获取同步统计信息
     */
    public SyncStatistics getSyncStatistics() {
        SyncStatistics stats = new SyncStatistics();
        
        for (ChannelSyncState channelState : channelStates.values()) {
            stats.addChannelStats(channelState.getChannelId(), 
                                channelState.getSyncDrift(),
                                channelState.getFrameCount(),
                                channelState.getErrorCount());
        }
        
        return stats;
    }
    
    /**
     * 停止同步管理器
     */
    public void shutdown() {
        synchronized (this) {
            if (!isSyncActive.get()) {
                Log.w(TAG, "Sync manager already stopped");
                return;
            }
            
            // 清理所有通道
            for (ChannelSyncState channelState : channelStates.values()) {
                channelState.cleanup();
            }
            channelStates.clear();
            
            // 停止各个控制器
            timeSyncController.shutdown();
            dataSyncController.shutdown();
            outputSyncController.shutdown();
            
            isSyncActive.set(false);
            Log.i(TAG, "AudioSyncManager shutdown completed");
        }
    }
    
    /**
     * 通道同步状态
     */
    private static class ChannelSyncState {
        private final String channelId;
        private volatile long lastActivityTime;
        private volatile long syncDrift;
        private volatile int frameCount;
        private volatile int errorCount;
        private volatile boolean isActive;
        
        public ChannelSyncState(String channelId) {
            this.channelId = channelId;
            this.lastActivityTime = System.currentTimeMillis();
            this.syncDrift = 0;
            this.frameCount = 0;
            this.errorCount = 0;
            this.isActive = true;
        }
        
        public void updateLastActivity() {
            this.lastActivityTime = System.currentTimeMillis();
        }
        
        public void processAudioFrame(byte[] encodedFrame) {
            if (!isActive) {
                return;
            }
            
            try {
                // 处理音频帧逻辑
                frameCount++;
                Log.d(TAG, "Processed frame " + frameCount + " for channel: " + channelId);
                
            } catch (Exception e) {
                errorCount++;
                Log.e(TAG, "Error processing frame for channel: " + channelId, e);
            }
        }
        
        public boolean isSynced() {
            long currentTime = System.currentTimeMillis();
            long timeSinceLastActivity = currentTime - lastActivityTime;
            
            // 检查是否在同步范围内
            return isActive && timeSinceLastActivity < 1000 && syncDrift < 50;
        }
        
        public void cleanup() {
            isActive = false;
        }
        
        // Getters
        public String getChannelId() { return channelId; }
        public long getSyncDrift() { return syncDrift; }
        public int getFrameCount() { return frameCount; }
        public int getErrorCount() { return errorCount; }
    }
    
    /**
     * 同步统计信息
     */
    public static class SyncStatistics {
        private final Map<String, ChannelStats> channelStats = new ConcurrentHashMap<>();
        
        public void addChannelStats(String channelId, long syncDrift, int frameCount, int errorCount) {
            channelStats.put(channelId, new ChannelStats(syncDrift, frameCount, errorCount));
        }
        
        public Map<String, ChannelStats> getChannelStats() {
            return new ConcurrentHashMap<>(channelStats);
        }
        
        public static class ChannelStats {
            public final long syncDrift;
            public final int frameCount;
            public final int errorCount;
            
            public ChannelStats(long syncDrift, int frameCount, int errorCount) {
                this.syncDrift = syncDrift;
                this.frameCount = frameCount;
                this.errorCount = errorCount;
            }
        }
    }
}
