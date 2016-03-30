package org.cache2k.impl;

/*
 * #%L
 * cache2k core package
 * %%
 * Copyright (C) 2000 - 2016 headissue GmbH, Munich
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public 
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

import org.cache2k.integration.AdvancedCacheLoader;
import org.cache2k.CacheEntry;
import org.cache2k.event.CacheEntryCreatedListener;
import org.cache2k.processor.CacheEntryProcessor;
import org.cache2k.event.CacheEntryRemovedListener;
import org.cache2k.event.CacheEntryUpdatedListener;
import org.cache2k.CacheManager;
import org.cache2k.integration.CacheWriter;
import org.cache2k.ClosableIterator;
import org.cache2k.integration.LoadCompletedListener;
import org.cache2k.impl.operation.ExaminationEntry;
import org.cache2k.impl.operation.Progress;
import org.cache2k.impl.operation.Semantic;
import org.cache2k.impl.operation.Specification;
import org.cache2k.impl.threading.Futures;
import org.cache2k.impl.util.Log;
import org.cache2k.storage.PurgeableStorage;
import org.cache2k.storage.StorageEntry;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A cache implementation that builds on a heap cache and coordinates with additional
 * attachments like storage, listeners and a writer.
 *
 * @author Jens Wilke
 */
public class WiredCache<K, V> extends AbstractCache<K, V>
  implements  StorageAdapter.Parent, HeapCacheListener<K,V> {

  @SuppressWarnings("unchecked")
  final Specification<K, V> SPEC = Specification.SINGLETON;

  BaseCache<K,V> heapCache;
  StorageAdapter storage;
  AdvancedCacheLoader<K,V> loader;
  CacheWriter<K, V> writer;
  CacheEntryRemovedListener<K,V>[] syncEntryRemovedListeners;
  CacheEntryCreatedListener<K,V>[] syncEntryCreatedListeners;
  CacheEntryUpdatedListener<K,V>[] syncEntryUpdatedListeners;
  RefreshHandler<K,V> refreshHandler;

  private CommonMetrics.Updater metrics() {
    return heapCache.metrics;
  }

  @Override
  public Log getLog() {
    return heapCache.getLog();
  }

  /** For testing */
  public BaseCache getHeapCache() {
    return heapCache;
  }

  @Override
  public String getName() {
    return heapCache.getName();
  }

  @Override
  public Class<?> getKeyType() {
    return heapCache.getKeyType();
  }

  @Override
  public Class<?> getValueType() {
    return heapCache.getValueType();
  }

  @Override
  public CacheManager getCacheManager() {
    return heapCache.getCacheManager();
  }

  @Override
  public V peekAndPut(K key, V value) {
    return returnValue(execute(key, SPEC.peekAndPut(key, value)));
  }

  @Override
  public V peekAndRemove(K key) {
    return returnValue(execute(key, SPEC.peekAndRemove(key)));
  }

  @Override
  public V peekAndReplace(K key, V value) {
    return returnValue(execute(key, SPEC.peekAndReplace(key, value)));
  }

  @Override
  public CacheEntry<K, V> peekEntry(K key) {
    return execute(key, SPEC.peekEntry(key));
  }

  @Override
  public void prefetch(final K key) {
    if (loader == null) {
      return;
    }
    if (!heapCache.isLoaderThreadAvailableForPrefetching()) {
      return;
    }
    Entry<K,V> e = heapCache.lookupEntrySynchronizedNoHitRecord(key);
    if (e != null && e.hasFreshData()) {
      return;
    }
    heapCache.loaderExecutor.execute(new BaseCache.RunWithCatch(this) {
      @Override
      public void action() {
        load(key);
      }
    });
  }

  @Override
  public void prefetchAll(Iterable<? extends K> keys) {
    if (loader == null) {
      return;
    }
    Set<K> _keysToLoad = heapCache.checkAllPresent(keys);
    for (K k : _keysToLoad) {
      if (!heapCache.isLoaderThreadAvailableForPrefetching()) {
        return;
      }
      final K key = k;
      Runnable r = new BaseCache.RunWithCatch(this) {
        @Override
        public void action() {
          load(key);
        }
      };
      heapCache.loaderExecutor.execute(r);
    }
  }

  private void load(final K key) {
    Entry<K, V> e = lookupQuick(key);
    if (e != null && e.hasFreshData()) {
      return;
    }
    load(key, e);
  }

  private void load(final K key, final Entry<K, V> _e) {
    execute(key, _e, SPEC.get(key));
  }

  @Override
  public boolean contains(K key) {
    return execute(key, SPEC.contains(key));
  }

  @Override
  public boolean putIfAbsent(K key, V value) {
    return execute(key, SPEC.putIfAbsent(key, value));
  }

  @Override
  public void put(K key, V value) {
    execute(key, SPEC.put(key, value));
  }

  @Override
  public void putAll(Map<? extends K, ? extends V> m) {
    for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
      put(e.getKey(), e.getValue());
    }
  }

  @Override
  public void remove(K key) {
    execute(key, SPEC.remove(key));
  }

  @Override
  public boolean removeIfEquals(K key, V value) {
    return execute(key, SPEC.remove(key, value));
  }

  @Override
  public boolean containsAndRemove(K key) {
    return execute(key, SPEC.containsAndRemove(key));
  }

  @Override
  public boolean replace(K key, V _newValue) {
    return execute(key, SPEC.replace(key, _newValue));
  }

  @Override
  public boolean replaceIfEquals(K key, V _oldValue, V _newValue) {
    return execute(key, SPEC.replace(key, _oldValue, _newValue));
  }

  @Override
  public CacheEntry<K, V> replaceOrGet(K key, V _oldValue, V _newValue, CacheEntry<K, V> _dummyEntry) {
    return execute(key, SPEC.replaceOrGet(key, _oldValue, _newValue, _dummyEntry));
  }

  @Override
  public void loadAll(final Iterable<? extends K> _keys, final LoadCompletedListener l) {
    checkLoaderPresent();
    final LoadCompletedListener _listener= l != null ? l : BaseCache.DUMMY_LOAD_COMPLETED_LISTENER;
    Set<K> _keysToLoad = heapCache.checkAllPresent(_keys);
    if (_keysToLoad.isEmpty()) {
      _listener.loadCompleted();
      return;
    }
    final AtomicInteger _countDown = new AtomicInteger(_keysToLoad.size());
    for (K k : _keysToLoad) {
      final K key = k;
      Runnable r = new BaseCache.RunWithCatch(this) {
        @Override
        public void action() {
          try {
            load(key);
          } finally {
            if (_countDown.decrementAndGet() == 0) {
              _listener.loadCompleted();
            }
          }
        }
      };
      heapCache.loaderExecutor.execute(r);
    }
  }

  @Override
  public void reloadAll(final Iterable<? extends K> _keys, final LoadCompletedListener l) {
    checkLoaderPresent();
    final LoadCompletedListener _listener= l != null ? l : BaseCache.DUMMY_LOAD_COMPLETED_LISTENER;
    Set<K> _keySet = heapCache.generateKeySet(_keys);
    final AtomicInteger _countDown = new AtomicInteger(_keySet.size());
    for (K k : _keySet) {
      final K key = k;
      Runnable r = new BaseCache.RunWithCatch(this) {
        @Override
        public void action() {
          try {
            execute(key, SPEC.UNCONDITIONAL_LOAD);
          } finally {
            if (_countDown.decrementAndGet() == 0) {
              _listener.loadCompleted();
            }
          }
        }
      };
      heapCache.loaderExecutor.execute(r);
    }
  }

  private void checkLoaderPresent() {
    if (loader == null) {
      throw new UnsupportedOperationException("loader not set");
    }
  }

  V returnValue(V v) {
    return heapCache.returnValue(v);
  }

  V returnValue(Entry<K,V> e) {
    return returnValue(e.getValueOrException());
  }

  Entry<K, V> lookupQuick(K key) {
    return heapCache.lookupEntryUnsynchronized(key);
  }


  @Override
  public V get(K key) {
    Entry<K, V> e = lookupQuick(key);
    if (e != null && e.hasFreshData()) {
      return returnValue(e);
    }
    return returnValue(execute(key, e, SPEC.get(key)));
   }

  /**
   * We need to deal with possible null values and exceptions. This is
   * a simple placeholder implementation that covers it all by working
   * on the entry.
   */
  @Override
  public Map<K, V> getAll(final Iterable<? extends K> keys) {
    prefetch(keys);
    Map<K, ExaminationEntry<K, V>> map = new HashMap<K, ExaminationEntry<K, V>>();
    for (K k : keys) {
      ExaminationEntry<K, V> e = execute(k, SPEC.getEntry(k));
      if (e != null) {
        map.put(k, e);
      }
    }
    return convertValueMap(map);
  }

  @Override
  public CacheEntry<K, V> getEntry(K key) {
    return execute(key, SPEC.getEntry(key));
  }

  @Override
  public int getTotalEntryCount() {
    synchronized (lockObject()) {
      if (storage != null) {
        return storage.getTotalEntryCount();
      }
      return heapCache.getLocalSize();
    }
  }

  @Override
  public <R> R invoke(K key, CacheEntryProcessor<K, V, R> entryProcessor, Object... args) {
    return execute(key, SPEC.invoke(key, loader != null, entryProcessor, args));
  }

  @Override
  public ClosableIterator<CacheEntry<K, V>> iterator() {
    ClosableIterator<CacheEntry<K, V>> tor;
    if (storage == null) {
      synchronized (lockObject()) {
         tor = new BaseCache.IteratorFilterEntry2Entry(heapCache, (ClosableIterator<Entry>) heapCache.iterateAllHeapEntries(), true);
      }
    } else {
      tor = new BaseCache.IteratorFilterEntry2Entry(heapCache, (ClosableIterator<Entry>) storage.iterateAll(), false);
    }
    final ClosableIterator<CacheEntry<K, V>> it = tor;
    ClosableIterator<CacheEntry<K, V>> _adapted = new ClosableIterator<CacheEntry<K, V>>() {

      CacheEntry<K, V> entry;

      @Override
      public void close() {
        it.close();
      }

      @Override
      public boolean hasNext() {
        return it.hasNext();
      }

      @Override
      public CacheEntry<K, V> next() {
        return entry = it.next();
      }

      @Override
      public void remove() {
        if (entry == null) {
          throw new IllegalStateException("call next first");
        }
        WiredCache.this.remove(entry.getKey());
      }
    };
    return _adapted;
  }

  @Override
  public V peek(K key) {
    Entry<K, V> e = lookupQuick(key);
    if (e != null && e.hasFreshData()) {
      return returnValue(e);
    }
    return returnValue(execute(key, SPEC.peek(key)));
  }

  /**
   * We need to deal with possible null values and exceptions. This is
   * a simple placeholder implementation that covers it all by working
   * on the entry.
   */
  @Override
  public Map<K, V> peekAll(final Set<? extends K> keys) {
    Map<K, ExaminationEntry<K, V>> map = new HashMap<K, ExaminationEntry<K, V>>();
    for (K k : keys) {
      ExaminationEntry<K, V> e = execute(k, SPEC.peekEntry(k));
      if (e != null) {
        map.put(k, e);
      }
    }
    return convertValueMap(map);
  }

  private Map<K, V> convertValueMap(final Map<K, ExaminationEntry<K, V>> _map) {
    return heapCache.convertValueMap(_map);
  }

  @Override
  public InternalCacheInfo getLatestInfo() {
    return heapCache.getLatestInfo();
  }

  @Override
  public InternalCacheInfo getInfo() {
    return heapCache.getInfo();
  }

  @Override
  public void clearTimingStatistics() {
    heapCache.clearTimingStatistics();
  }

  @Override
  public void checkIntegrity() {
    heapCache.checkIntegrity();
  }

  @Override
  public void destroy() {
    close();
  }

  @Override
  public boolean isClosed() {
    return heapCache.isClosed();
  }

  @Override
  public String toString() {
    return heapCache.toString();
  }

  public void init() {
    if (storage == null && heapCache.maxSize == 0) {
      throw new IllegalArgumentException("maxElements must be >0");
    }
    if (storage != null) {
      storage.open();
    }
    refreshHandler.init(this);
    heapCache.init();
  }

  @Override
  public Future<Void> cancelTimerJobs() {
    synchronized (lockObject()) {
      Future<Void> _waitFuture = heapCache.cancelTimerJobs();
      if (storage != null) {
        Future<Void> f = storage.cancelTimerJobs();
        if (f != null) {
          _waitFuture = new Futures.WaitForAllFuture(_waitFuture, f);
        }
      }
      return _waitFuture;
    }
  }

  @Override
  public void purge() {
    if (storage != null) {
      storage.purge();
    }
  }

  @Override
  public void flush() {
    if (storage != null) {
      storage.flush();
    }
  }

  @Override
  public void clear() {
    if (storage != null) {
      storage.clear();
      return;
    }
    synchronized (lockObject()) {
      heapCache.checkClosed();
      heapCache.clearLocalCache();
      refreshHandler.shutdown();
      refreshHandler.init(this);
    }
  }

  @Override
  public void close() {
    heapCache.closePart1();
    Future<Void> _waitForStorage = null;
    if (storage != null) {
      _waitForStorage = storage.shutdown();
    }
    if (_waitForStorage != null) {
      try {
        _waitForStorage.get();
      } catch (Exception ex) {
        StorageAdapter.rethrow("shutdown", ex);
      }
    }
    synchronized (lockObject()) {
      storage = null;
    }
    refreshHandler.shutdown();
    heapCache.closePart2();
  }

  private Object lockObject() {
    return heapCache.lock;
  }

  @Override
  public void resetStorage(StorageAdapter _from, StorageAdapter to) {
    synchronized (lockObject()) {
      storage = to;
    }
  }

  @Override
  public StorageAdapter getStorage() {
    return storage;
  }

  /**
   * Insert a cache entry for the given key and run action under the entry
   * lock. If the cache entry has fresh data, we do not run the action.
   * Called from storage. The entry referenced by the key is expired and
   * will be purged.
   */
  public void lockAndRunForPurge(K key, PurgeableStorage.PurgeAction _action) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void onEvictionFromHeap(final Entry<K, V> e) {
    boolean _storeEvenImmediatelyExpired =
      heapCache.hasKeepAfterExpired() && (e.isDataValid() || e.isExpired());
    boolean _shouldStore =
      (storage != null) && (_storeEvenImmediatelyExpired || e.hasFreshData());
    if (_shouldStore) {
      storage.evict(e);
    }
  }

  /**
   * Used by the storage iterator to insert the entry in the cache.
   */
  public Entry<K,V> insertEntryFromStorage(final StorageEntry se) {
    Semantic<K,V, Entry<K,V>> op = new Semantic.Read<K, V, Entry<K, V>>() {
      @Override
      public void examine(final Progress<V, Entry<K, V>> c, final ExaminationEntry<K, V> e) {
        c.result((Entry<K, V>) e);
      }
    };
    EntryAction<K, V, Entry<K,V>> _action = new MyEntryAction<Entry<K,V>>(op, (K) se.getKey(), null) {
      @Override
      public void storageRead() {
        onReadSuccess(se);
      }
    };
    Entry<K,V> e = execute(op, _action);
    return e;
  }

  @Override
  protected <R> EntryAction<K, V, R> createEntryAction(final K key, final Entry<K, V> e, final Semantic<K, V, R> op) {
    return new MyEntryAction<R>(op, key, e);
  }

  @Override
  public String getEntryState(final K key) {
    return heapCache.getEntryState(key);
  }

  @Override
  public void timerEventExpireEntry(final Entry<K,V> e) {
    heapCache.timerEventExpireEntry(e);
  }

  @Override
  public void timerEventRefresh(final Entry<K,V> e) {
    metrics().timerEvent();
    synchronized (e) {
      if (e.isGone()) {
        return;
      }
      if (heapCache.moveToRefreshHash(e)) {
        Runnable r = new Runnable() {
          @Override
          public void run() {
            if (storage == null) {
              synchronized (e) {
                e.waitForProcessing();
                if (e.isGone()) {
                  return;
                }
              }
            }
            try {
              execute(e.getKey(), e, SPEC.UNCONDITIONAL_LOAD);
              synchronized (lockObject()) {
                heapCache.refreshCnt++;
              }
            } catch (CacheClosedException ignore) {
            } catch (Throwable ex) {
              synchronized (lockObject()) {
                heapCache.internalExceptionCnt++;
              }
              getLog().warn("Refresh exception", ex);
              try {
                heapCache.expireEntry(e);
              } catch (CacheClosedException ignore) {
              }
            }
          }
        };
        try {
          heapCache.loaderExecutor.execute(r);
          return;
        } catch (RejectedExecutionException ignore) {
        }
        heapCache.refreshSubmitFailedCnt++;
      } else { // if (mainHashCtrl.remove(mainHash, e)) ...
      }
      heapCache.timerEventExpireEntryLocked(e);
    }
  }

  /**
   * Wire the entry action to the resources of this cache.
   */
  class MyEntryAction<R> extends EntryAction<K,V, R> {

    public MyEntryAction(final Semantic<K, V, R> op, final K _k, final Entry<K, V> e) {
      super(WiredCache.this.heapCache, WiredCache.this, op, _k, e);
    }

    @Override
    protected boolean mightHaveListeners() {
      return true;
    }

    @Override
    protected CacheEntryCreatedListener<K, V>[] entryCreatedListeners() {
      return syncEntryCreatedListeners;
    }

    @Override
    protected CacheEntryRemovedListener<K, V>[] entryRemovedListeners() {
      return syncEntryRemovedListeners;
    }

    @Override
    protected CacheEntryUpdatedListener<K, V>[] entryUpdatedListeners() {
      return syncEntryUpdatedListeners;
    }

    @Override
    protected StorageAdapter storage() {
      return storage;
    }

    @Override
    protected CacheWriter<K, V> writer() {
      return writer;
    }

    @Override
    protected RefreshHandler<K, V> refreshHandler() {
      return refreshHandler;
    }
  }

}
