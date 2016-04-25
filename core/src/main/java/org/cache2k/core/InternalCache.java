package org.cache2k.core;

/*
 * #%L
 * cache2k core
 * %%
 * Copyright (C) 2000 - 2016 headissue GmbH, Munich
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import org.cache2k.Cache;
import org.cache2k.CacheEntry;
import org.cache2k.core.util.Log;
import org.cache2k.core.storageApi.StorageAdapter;

import java.util.concurrent.Future;

/**
 * Interface to extended cache functions for the internal components.
 *
 * @author Jens Wilke
 */
public interface InternalCache<K, V> extends Cache<K, V>, CanCheckIntegrity {

  /** used from the cache manager */
  Log getLog();

  String getName();

  StorageAdapter getStorage();

  Class<?> getKeyType();

  Class<?> getValueType();

  void clearTimingStatistics();

  /** used from the cache manager for shutdown */
  Future<Void> cancelTimerJobs();

  void timerEventRefresh(Entry<K, V> e);

  void timerEventExpireEntry(Entry<K, V> e);

  InternalCacheInfo getInfo();

  InternalCacheInfo getLatestInfo();

  /**
   * Used by JCache impl, since access needs to trigger the TTI maybe use EP instead?
   */
  CacheEntry<K, V> replaceOrGet(K key, V _oldValue, V _newValue, CacheEntry<K, V> _dummyEntry);

  String getEntryState(K key);

}