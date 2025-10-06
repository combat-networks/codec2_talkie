package com.radio.codec2talkie.sync;

import android.util.Log;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * 时间同步控制器
 * 负责管理多路音频的时间戳同步
 */
public class TimeSyncController {
    private static final String TAG = "TimeSyncController";
    
    // 时间同步参数
    private static final long SYNC_INTERVAL_MS = 10;           // 10ms同步间隔
    private static final long MAX_SYNC_DRIFT_MS = 5;           // 最大同步漂移5ms
    private static final long SYNC_TIMEOUT_MS = 1000;          // 同步超时1秒
    
    // 同步状态
    private final AtomicBoolean isSyncActive = new AtomicBoolean(false);
    private volatile long masterTimestamp = 0;                 // 主时间戳
    private final Object syncLock = new Object();              // 同步锁
    
    // 多路音频时间戳
    private final Map<String, Long> channelTimestamps = new ConcurrentHashMap<>();
    private final Map<String, Long> channelDrifts = new ConcurrentHashMap<>();
    private final Map<String, Long> channelLastUpdate = new ConcurrentHashMap<>();
    
    // 同步定时器
    private Timer syncTimer;
    
    /**
     * 初始化时间同步
     */
    public void initializeSync() {
        synchronized (syncLock) {
            if (isSyncActive.get()) {
                Log.w(TAG, "Time sync already initialized");
                return;
            }
            
            masterTimestamp = System.currentTimeMillis();
            isSyncActive.set(true);
            
            // 启动同步定时器
            startSyncTimer();
            
            Log.i(TAG, "Time sync initialized with master timestamp: " + masterTimestamp);
        }
    }
    
    /**
     * 启动同步定时器
     */
    private void startSyncTimer() {
        syncTimer = new Timer("TimeSyncTimer");
        syncTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                performSyncCheck();
            }
        }, SYNC_INTERVAL_MS, SYNC_INTERVAL_MS);
    }
    
    /**
     * 执行同步检查
     */
    private void performSyncCheck() {
        if (!isSyncActive.get()) {
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        
        synchronized (syncLock) {
            // 检查所有通道的同步状态
            for (String channelId : channelTimestamps.keySet()) {
                Long lastUpdate = channelLastUpdate.get(channelId);
                if (lastUpdate != null && (currentTime - lastUpdate) > SYNC_TIMEOUT_MS) {
                    Log.w(TAG, "Channel " + channelId + " sync timeout");
                    // 可以在这里实现超时处理逻辑
                }
            }
        }
    }
    
    /**
     * 注册音频通道时间戳
     */
    public void registerChannelTimestamp(String channelId, long timestamp) {
        if (!isSyncActive.get()) {
            Log.w(TAG, "Sync not active, ignoring timestamp for channel: " + channelId);
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        long drift = Math.abs(currentTime - timestamp);
        
        synchronized (syncLock) {
            channelTimestamps.put(channelId, timestamp);
            channelDrifts.put(channelId, drift);
            channelLastUpdate.put(channelId, currentTime);
            
            // 检查同步漂移
            if (drift > MAX_SYNC_DRIFT_MS) {
                Log.w(TAG, "Channel " + channelId + " has sync drift: " + drift + "ms");
                adjustChannelSync(channelId, drift);
            }
        }
        
        Log.d(TAG, "Registered timestamp for channel " + channelId + ": " + timestamp + " (drift: " + drift + "ms)");
    }
    
    /**
     * 调整通道同步
     */
    private void adjustChannelSync(String channelId, long drift) {
        // 实现同步调整逻辑
        // 这里可以根据需要实现不同的调整策略
        
        if (drift > MAX_SYNC_DRIFT_MS * 2) {
            // 大漂移，需要重新同步
            Log.i(TAG, "Large drift detected for channel " + channelId + ", resyncing");
            resyncChannel(channelId);
        } else {
            // 小漂移，进行微调
            Log.i(TAG, "Adjusting sync for channel " + channelId + " by " + drift + "ms");
            // 这里可以实现微调逻辑
        }
    }
    
    /**
     * 重新同步通道
     */
    private void resyncChannel(String channelId) {
        synchronized (syncLock) {
            long currentTime = System.currentTimeMillis();
            channelTimestamps.put(channelId, currentTime);
            channelDrifts.put(channelId, 0L);
            channelLastUpdate.put(channelId, currentTime);
            
            Log.i(TAG, "Resynced channel: " + channelId);
        }
    }
    
    /**
     * 获取同步后的时间戳
     */
    public long getSyncedTimestamp(String channelId) {
        synchronized (syncLock) {
            Long channelTimestamp = channelTimestamps.get(channelId);
            if (channelTimestamp == null) {
                return masterTimestamp;
            }
            
            Long drift = channelDrifts.get(channelId);
            if (drift != null && drift > MAX_SYNC_DRIFT_MS) {
                // 应用同步调整
                return channelTimestamp + drift;
            }
            
            return channelTimestamp;
        }
    }
    
    /**
     * 检查所有通道是否同步
     */
    public boolean areAllChannelsSynced() {
        synchronized (syncLock) {
            if (channelTimestamps.isEmpty()) {
                return false;
            }
            
            long currentTime = System.currentTimeMillis();
            long maxDrift = 0;
            
            for (String channelId : channelTimestamps.keySet()) {
                Long drift = channelDrifts.get(channelId);
                if (drift != null) {
                    maxDrift = Math.max(maxDrift, drift);
                }
                
                // 检查超时
                Long lastUpdate = channelLastUpdate.get(channelId);
                if (lastUpdate != null && (currentTime - lastUpdate) > SYNC_TIMEOUT_MS) {
                    Log.w(TAG, "Channel " + channelId + " sync timeout");
                    return false;
                }
            }
            
            return maxDrift <= MAX_SYNC_DRIFT_MS;
        }
    }
    
    /**
     * 获取通道同步漂移
     */
    public long getChannelDrift(String channelId) {
        synchronized (syncLock) {
            Long drift = channelDrifts.get(channelId);
            return drift != null ? drift : 0;
        }
    }
    
    /**
     * 获取同步统计信息
     */
    public SyncTimeStatistics getSyncStatistics() {
        synchronized (syncLock) {
            SyncTimeStatistics stats = new SyncTimeStatistics();
            stats.masterTimestamp = masterTimestamp;
            stats.channelCount = channelTimestamps.size();
            stats.maxDrift = 0;
            stats.averageDrift = 0;
            
            if (!channelTimestamps.isEmpty()) {
                long totalDrift = 0;
                for (Long drift : channelDrifts.values()) {
                    if (drift != null) {
                        stats.maxDrift = Math.max(stats.maxDrift, drift);
                        totalDrift += drift;
                    }
                }
                stats.averageDrift = totalDrift / channelTimestamps.size();
            }
            
            return stats;
        }
    }
    
    /**
     * 强制同步所有通道
     */
    public void forceSyncAllChannels() {
        synchronized (syncLock) {
            long currentTime = System.currentTimeMillis();
            masterTimestamp = currentTime;
            
            for (String channelId : channelTimestamps.keySet()) {
                channelTimestamps.put(channelId, currentTime);
                channelDrifts.put(channelId, 0L);
                channelLastUpdate.put(channelId, currentTime);
            }
            
            Log.i(TAG, "Forced sync for all channels at timestamp: " + currentTime);
        }
    }
    
    /**
     * 关闭时间同步
     */
    public void shutdown() {
        synchronized (syncLock) {
            if (!isSyncActive.get()) {
                Log.w(TAG, "Time sync already stopped");
                return;
            }
            
            isSyncActive.set(false);
            
            if (syncTimer != null) {
                syncTimer.cancel();
                syncTimer = null;
            }
            
            channelTimestamps.clear();
            channelDrifts.clear();
            channelLastUpdate.clear();
            
            Log.i(TAG, "Time sync shutdown completed");
        }
    }
    
    /**
     * 同步时间统计信息
     */
    public static class SyncTimeStatistics {
        public long masterTimestamp;
        public int channelCount;
        public long maxDrift;
        public long averageDrift;
        
        @Override
        public String toString() {
            return String.format("SyncTimeStats[master=%d, channels=%d, maxDrift=%d, avgDrift=%d]",
                    masterTimestamp, channelCount, maxDrift, averageDrift);
        }
    }
}
