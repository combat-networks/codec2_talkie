package com.radio.codec2talkie.sync;

import android.util.Log;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

/**
 * 数据同步控制器
 * 负责管理多路音频数据的同步处理
 */
public class DataSyncController {
    private static final String TAG = "DataSyncController";
    
    // 数据同步参数
    private static final int MAX_BUFFER_SIZE = 1024;            // 最大缓冲区大小
    private static final int SYNC_TIMEOUT_MS = 100;             // 同步超时100ms
    private static final int MAX_RETRY_COUNT = 3;               // 最大重试次数
    private static final long SYNC_CHECK_INTERVAL_MS = 50;      // 同步检查间隔50ms
    
    // 同步状态
    private final AtomicBoolean isDataSyncActive = new AtomicBoolean(false);
    private final Object dataSyncLock = new Object();           // 数据同步锁
    
    // 多路音频数据缓冲区
    private final Map<String, BlockingQueue<AudioFrame>> channelBuffers = new ConcurrentHashMap<>();
    private final Map<String, Long> channelLastDataTime = new ConcurrentHashMap<>();
    private final Map<String, Integer> channelErrorCounts = new ConcurrentHashMap<>();
    
    // 同步定时器
    private Timer syncTimer;
    
    /**
     * 初始化数据同步
     */
    public void initializeDataSync() {
        synchronized (dataSyncLock) {
            if (isDataSyncActive.get()) {
                Log.w(TAG, "Data sync already initialized");
                return;
            }
            
            isDataSyncActive.set(true);
            
            // 启动同步检查定时器
            startSyncTimer();
            
            Log.i(TAG, "Data sync initialized");
        }
    }
    
    /**
     * 启动同步定时器
     */
    private void startSyncTimer() {
        syncTimer = new Timer("DataSyncTimer");
        syncTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                performSyncCheck();
            }
        }, SYNC_CHECK_INTERVAL_MS, SYNC_CHECK_INTERVAL_MS);
    }
    
    /**
     * 执行同步检查
     */
    private void performSyncCheck() {
        if (!isDataSyncActive.get()) {
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        
        synchronized (dataSyncLock) {
            // 检查所有通道的数据同步状态
            for (String channelId : channelLastDataTime.keySet()) {
                Long lastDataTime = channelLastDataTime.get(channelId);
                if (lastDataTime != null && (currentTime - lastDataTime) > SYNC_TIMEOUT_MS) {
                    Log.w(TAG, "Channel " + channelId + " data sync timeout");
                    handleChannelTimeout(channelId);
                }
            }
        }
    }
    
    /**
     * 处理通道超时
     */
    private void handleChannelTimeout(String channelId) {
        synchronized (dataSyncLock) {
            Integer errorCount = channelErrorCounts.merge(channelId, 1, Integer::sum);
            
            if (errorCount > MAX_RETRY_COUNT) {
                Log.e(TAG, "Channel " + channelId + " exceeded max retry count, disabling");
                // 可以在这里实现通道禁用逻辑
            } else {
                Log.w(TAG, "Channel " + channelId + " timeout, retry count: " + errorCount);
            }
        }
    }
    
    /**
     * 注册音频通道数据缓冲区
     */
    public void registerChannelBuffer(String channelId) {
        synchronized (dataSyncLock) {
            if (channelBuffers.containsKey(channelId)) {
                Log.w(TAG, "Channel buffer already registered: " + channelId);
                return;
            }
            
            channelBuffers.put(channelId, new LinkedBlockingQueue<>(MAX_BUFFER_SIZE));
            channelLastDataTime.put(channelId, 0L);
            channelErrorCounts.put(channelId, 0);
            
            Log.i(TAG, "Registered buffer for channel: " + channelId);
        }
    }
    
    /**
     * 注销音频通道数据缓冲区
     */
    public void unregisterChannelBuffer(String channelId) {
        synchronized (dataSyncLock) {
            BlockingQueue<AudioFrame> buffer = channelBuffers.remove(channelId);
            if (buffer != null) {
                // 清空缓冲区
                buffer.clear();
            }
            
            channelLastDataTime.remove(channelId);
            channelErrorCounts.remove(channelId);
            
            Log.i(TAG, "Unregistered buffer for channel: " + channelId);
        }
    }
    
    /**
     * 添加音频帧到缓冲区
     */
    public boolean addAudioFrame(String channelId, AudioFrame frame) {
        if (!isDataSyncActive.get()) {
            Log.w(TAG, "Data sync not active");
            return false;
        }
        
        BlockingQueue<AudioFrame> buffer = channelBuffers.get(channelId);
        if (buffer == null) {
            Log.e(TAG, "No buffer found for channel: " + channelId);
            return false;
        }
        
        try {
            boolean added = buffer.offer(frame, SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (added) {
                channelLastDataTime.put(channelId, System.currentTimeMillis());
                Log.d(TAG, "Added frame to channel " + channelId + " buffer");
            } else {
                Log.w(TAG, "Failed to add frame to channel " + channelId + " buffer (timeout)");
                handleChannelTimeout(channelId);
            }
            return added;
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while adding frame to channel " + channelId, e);
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    /**
     * 获取同步的音频帧
     */
    public List<AudioFrame> getSyncedFrames() {
        List<AudioFrame> syncedFrames = new ArrayList<>();
        
        synchronized (dataSyncLock) {
            if (!isDataSyncActive.get()) {
                return syncedFrames;
            }
            
            // 检查所有通道是否有数据
            boolean allChannelsHaveData = true;
            for (String channelId : channelBuffers.keySet()) {
                BlockingQueue<AudioFrame> buffer = channelBuffers.get(channelId);
                if (buffer.isEmpty()) {
                    allChannelsHaveData = false;
                    break;
                }
            }
            
            if (allChannelsHaveData) {
                // 从所有通道获取同步帧
                for (String channelId : channelBuffers.keySet()) {
                    BlockingQueue<AudioFrame> buffer = channelBuffers.get(channelId);
                    AudioFrame frame = buffer.poll();
                    if (frame != null) {
                        syncedFrames.add(frame);
                        Log.d(TAG, "Retrieved synced frame from channel: " + channelId);
                    }
                }
            }
        }
        
        return syncedFrames;
    }
    
    /**
     * 获取指定通道的音频帧
     */
    public AudioFrame getChannelFrame(String channelId) {
        BlockingQueue<AudioFrame> buffer = channelBuffers.get(channelId);
        if (buffer == null) {
            Log.e(TAG, "No buffer found for channel: " + channelId);
            return null;
        }
        
        return buffer.poll();
    }
    
    /**
     * 检查数据同步状态
     */
    public boolean isDataSynced() {
        synchronized (dataSyncLock) {
            if (channelBuffers.isEmpty()) {
                return false;
            }
            
            long currentTime = System.currentTimeMillis();
            for (String channelId : channelLastDataTime.keySet()) {
                Long lastDataTime = channelLastDataTime.get(channelId);
                if (lastDataTime == null || (currentTime - lastDataTime) > SYNC_TIMEOUT_MS) {
                    Log.w(TAG, "Channel " + channelId + " data sync timeout");
                    return false;
                }
            }
            
            return true;
        }
    }
    
    /**
     * 检查指定通道是否同步
     */
    public boolean isChannelSynced(String channelId) {
        synchronized (dataSyncLock) {
            Long lastDataTime = channelLastDataTime.get(channelId);
            if (lastDataTime == null) {
                return false;
            }
            
            long currentTime = System.currentTimeMillis();
            return (currentTime - lastDataTime) <= SYNC_TIMEOUT_MS;
        }
    }
    
    /**
     * 获取通道缓冲区状态
     */
    public BufferStatus getChannelBufferStatus(String channelId) {
        synchronized (dataSyncLock) {
            BlockingQueue<AudioFrame> buffer = channelBuffers.get(channelId);
            if (buffer == null) {
                return new BufferStatus(0, 0, false);
            }
            
            int currentSize = buffer.size();
            int maxSize = MAX_BUFFER_SIZE;
            boolean isFull = buffer.remainingCapacity() == 0;
            
            return new BufferStatus(currentSize, maxSize, isFull);
        }
    }
    
    /**
     * 获取所有通道的缓冲区状态
     */
    public Map<String, BufferStatus> getAllChannelBufferStatus() {
        Map<String, BufferStatus> statusMap = new ConcurrentHashMap<>();
        
        synchronized (dataSyncLock) {
            for (String channelId : channelBuffers.keySet()) {
                statusMap.put(channelId, getChannelBufferStatus(channelId));
            }
        }
        
        return statusMap;
    }
    
    /**
     * 清空指定通道的缓冲区
     */
    public void clearChannelBuffer(String channelId) {
        synchronized (dataSyncLock) {
            BlockingQueue<AudioFrame> buffer = channelBuffers.get(channelId);
            if (buffer != null) {
                buffer.clear();
                Log.i(TAG, "Cleared buffer for channel: " + channelId);
            }
        }
    }
    
    /**
     * 清空所有通道的缓冲区
     */
    public void clearAllChannelBuffers() {
        synchronized (dataSyncLock) {
            for (BlockingQueue<AudioFrame> buffer : channelBuffers.values()) {
                buffer.clear();
            }
            Log.i(TAG, "Cleared all channel buffers");
        }
    }
    
    /**
     * 重置通道错误计数
     */
    public void resetChannelErrors(String channelId) {
        synchronized (dataSyncLock) {
            channelErrorCounts.put(channelId, 0);
            Log.i(TAG, "Reset error count for channel: " + channelId);
        }
    }
    
    /**
     * 获取数据同步统计信息
     */
    public DataSyncStatistics getSyncStatistics() {
        synchronized (dataSyncLock) {
            DataSyncStatistics stats = new DataSyncStatistics();
            stats.channelCount = channelBuffers.size();
            stats.totalBuffers = channelBuffers.size();
            stats.activeChannels = 0;
            stats.errorChannels = 0;
            
            long currentTime = System.currentTimeMillis();
            for (String channelId : channelBuffers.keySet()) {
                Long lastDataTime = channelLastDataTime.get(channelId);
                if (lastDataTime != null && (currentTime - lastDataTime) <= SYNC_TIMEOUT_MS) {
                    stats.activeChannels++;
                }
                
                Integer errorCount = channelErrorCounts.get(channelId);
                if (errorCount != null && errorCount > 0) {
                    stats.errorChannels++;
                }
            }
            
            return stats;
        }
    }
    
    /**
     * 关闭数据同步
     */
    public void shutdown() {
        synchronized (dataSyncLock) {
            if (!isDataSyncActive.get()) {
                Log.w(TAG, "Data sync already stopped");
                return;
            }
            
            isDataSyncActive.set(false);
            
            if (syncTimer != null) {
                syncTimer.cancel();
                syncTimer = null;
            }
            
            // 清空所有缓冲区
            clearAllChannelBuffers();
            
            channelBuffers.clear();
            channelLastDataTime.clear();
            channelErrorCounts.clear();
            
            Log.i(TAG, "Data sync shutdown completed");
        }
    }
    
    /**
     * 缓冲区状态
     */
    public static class BufferStatus {
        public final int currentSize;
        public final int maxSize;
        public final boolean isFull;
        
        public BufferStatus(int currentSize, int maxSize, boolean isFull) {
            this.currentSize = currentSize;
            this.maxSize = maxSize;
            this.isFull = isFull;
        }
        
        @Override
        public String toString() {
            return String.format("BufferStatus[size=%d/%d, full=%s]", 
                    currentSize, maxSize, isFull);
        }
    }
    
    /**
     * 数据同步统计信息
     */
    public static class DataSyncStatistics {
        public int channelCount;
        public int totalBuffers;
        public int activeChannels;
        public int errorChannels;
        
        @Override
        public String toString() {
            return String.format("DataSyncStats[channels=%d, active=%d, errors=%d]", 
                    channelCount, activeChannels, errorChannels);
        }
    }
}
