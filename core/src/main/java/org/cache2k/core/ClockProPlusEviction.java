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

import org.cache2k.configuration.Cache2kConfiguration;
import org.cache2k.core.util.TunableConstants;
import org.cache2k.core.util.TunableFactory;

/**
 * CLOCK Pro implementation with 3 clocks. Using separate clocks for hot and cold
 * has saves us the extra marker (4 bytes in Java) for an entry to decide whether it is in hot
 * or cold. OTOH we shuffle around the entries in different lists and loose the order they
 * were inserted, which leads to less cache efficiency.
 *
 * <p/>This version uses a static allocation for hot and cold spaces. No online or dynamic
 * optimization is done yet. However, the hitrate for all measured access traces is better
 * then LRU and it is resistant to scans.
 *
 * @author Jens Wilke; created: 2013-07-12
 */
public class ClockProPlusEviction extends AbstractEviction {

  private static final Tunable TUNABLE_CLOCK_PRO = TunableFactory.get(Tunable.class);

  long hotHits;
  long coldHits;
  long ghostHits;

  long hotRunCnt;
  long hot24hCnt;
  long hotScanCnt;
  long coldRunCnt;
  long cold24hCnt;
  long coldScanCnt;

  int coldSize;
  int hotSize;

  /** Maximum size of hot clock. 0 means normal clock behaviour */
  long hotMax;
  long ghostMax;

  Entry handCold;
  Entry handHot;

  Ghost[] ghosts;
  Ghost ghostHead = new Ghost().shortCurcuit();
  int ghostSize = 0;
  static final int GHOST_LOAD_PERCENT = 80;

  public ClockProPlusEviction(final HeapCache _heapCache, final HeapCacheListener _listener, final Cache2kConfiguration cfg, int _segmentsCount) {
    super(_heapCache, _listener, cfg, _segmentsCount);
    ghostMax = maxSize / 2 + 1;
    hotMax = maxSize * TUNABLE_CLOCK_PRO.hotMaxPercentage / 100;
    coldSize = 0;
    hotSize = 0;
    handCold = null;
    handHot = null;
    ghosts = new Ghost[4];
  }

  private long sumUpListHits(Entry e) {
    if (e == null) { return 0; }
    long cnt = 0;
    Entry _head = e;
    do {
      cnt += e.hitCnt;
      e = e.next;
    } while (e != _head);
    return cnt;
  }

  @Override
  public long getHitCount() {
    return hotHits + coldHits + sumUpListHits(handCold) + sumUpListHits(handHot);
  }

  @Override
  public long removeAll() {
    Entry e, _head;
    int _count = 0;
    e = _head = handCold;
    long _hits = 0;
    if (e != null) {
      do {
        _hits += e.hitCnt;
        Entry _next = e.prev;
        e.removedFromList();
        _count++;
        e = _next;
      } while (e != _head);
      coldHits += _hits;
    }
    handCold = null;
    coldSize = 0;
    e = _head = handHot;
    if (e != null) {
      _hits = 0;
      do {
        _hits += e.hitCnt;
        Entry _next = e.prev;
        e.removedFromList();
        _count++;
        e = _next;
      } while (e != _head);
      hotHits += _hits;
    }
    handHot = null;
    hotSize = 0;
    return _count;
  }

  /**
   * Track the entry on the ghost list and call the usual remove procedure.
   */
  @Override
  public void evictEntry(final Entry e) {
    insertCopyIntoGhosts(e);
    removeEntryFromReplacementList(e);
  }

  /**
   * Remove, expire or eviction of an entry happens. Remove the entry from the
   * replacement list data structure. We can just remove the entry from the list,
   * but we don't know which list it is in to correct the counters accordingly.
   * So, instead of removing it directly, we just mark it and remove it
   * by the normal eviction process.
   *
   * <p>Why don't generate ghosts here? If the entry is removed because of
   * a programmatic remove or expiry we should not occupy any resources.
   * Removing and expiry may also take place when no eviction is needed at all,
   * which happens when the cache size did not hit the maximum yet. Producing ghosts
   * would add additional overhead, when it is not needed.
   */
  @Override
  protected void removeEntryFromReplacementList(Entry e) {
    if (e.isHot()) {
      hotHits += e.hitCnt;
      handHot = Entry.removeFromCyclicList(handHot, e);
      hotSize--;
    } else {
      coldHits += e.hitCnt;
      handCold = Entry.removeFromCyclicList(handCold, e);
      coldSize--;
    }
  }

  private void insertCopyIntoGhosts(Entry e) {
    int hc = e.hashCode;
    Ghost g = lookupGhost(hc);
    if (g != null) {
      /*
       * either this is a hash code collision, or a previous ghost hit that was not removed.
       */
      Ghost.moveToFront(ghostHead, g);
      return;
    }
    if (ghostSize >= ghostMax) {
      g = ghostHead.prev;
      Ghost.removeFromList(g);
      boolean f = removeGhost(g, g.hash);
    } else {
      g = new Ghost();
    }
    g.hash = hc;
    insertGhost(g, hc);
    Ghost.insertInList(ghostHead, g);
  }

  public long getSize() {
    return hotSize + coldSize;
  }

  @Override
  protected void insertIntoReplacementList(Entry e) {
    Ghost g = lookupGhost(e.hashCode);
    if (g != null) {
      /*
       * don't remove ghosts here, save object allocations.
       */
      if (false) {
        removeGhost(g, g.hash);
        Ghost.removeFromList(g);
      }
      ghostHits++;
      e.setHot(true);
      hotSize++;
      handHot = Entry.insertIntoTailCyclicList(handHot, e);
      return;
    }
    coldSize++;
    handCold = Entry.insertIntoTailCyclicList(handCold, e);
  }

  protected Entry runHandHot() {
    hotRunCnt++;
    Entry _handStart = handHot;
    Entry _hand = _handStart;
    Entry _coldCandidate = _hand;
    long _lowestHits = Long.MAX_VALUE;
    long _hotHits = hotHits;
    int _scanCnt = -1;
    long _decrease = ((_hand.hitCnt + _hand.next.hitCnt) >> TUNABLE_CLOCK_PRO.hitCounterDecreaseShift) + 1;
    do {
      _scanCnt++;
      long _hitCnt = _hand.hitCnt;
      if (_hitCnt < _lowestHits) {
        _lowestHits = _hitCnt;
        _coldCandidate = _hand;
        if (_hitCnt == 0) {
          break;
        }
      }
      if (_hitCnt < _decrease) {
        _hand.hitCnt = 0;
        _hotHits += _hitCnt;
      } else {
        _hand.hitCnt = _hitCnt - _decrease;
        _hotHits += _decrease;
      }
      _hand = _hand.next;
    } while (_hand != _handStart);
    hotHits = _hotHits;
    hotScanCnt += _scanCnt;
    if (_scanCnt == hotMax ) {
      hot24hCnt++; // count a full clock cycle
    }
    handHot = Entry.removeFromCyclicList(_hand, _coldCandidate);
    hotSize--;
    _coldCandidate.setHot(false);
    return _coldCandidate;
  }

  /**
   * Runs cold hand an in turn hot hand to find eviction candidate.
   */
  @Override
  protected Entry findEvictionCandidate(Entry _previous) {
    coldRunCnt++;
    Entry _hand = handCold;
    int _scanCnt = 0;
    if (_hand == null) {
      _hand = refillFromHot(_hand);
    }
    if (_hand.hitCnt > 0) {
      _hand = refillFromHot(_hand);
      do {
        _scanCnt++;
        coldHits += _hand.hitCnt;
        _hand.hitCnt = 0;
        Entry e = _hand;
        _hand = Entry.removeFromCyclicList(e);
        coldSize--;
        e.setHot(true);
        hotSize++;
        handHot = Entry.insertIntoTailCyclicList(handHot, e);
      } while (_hand != null && _hand.hitCnt > 0);
    }

    if (_hand == null) {
      _hand = refillFromHot(_hand);
    }
    if (_scanCnt > this.coldSize) {
      cold24hCnt++;
    }
    coldScanCnt += _scanCnt;
    handCold = _hand.next;
    return _hand;
  }

  private Entry refillFromHot(Entry _hand) {
    while (hotSize > hotMax || _hand == null) {
      Entry e = runHandHot();
      if (e != null) {
        _hand =  Entry.insertIntoTailCyclicList(_hand, e);
        coldSize++;
      }
    }
    return _hand;
  }

  @Override
  public void checkIntegrity(final IntegrityState _integrityState) {
    _integrityState
      .checkEquals("ghostSize == countGhostsInHash()", ghostSize, countGhostsInHash())
      .check("hotMax <= maxElements", hotMax <= maxSize)
      .check("checkCyclicListIntegrity(handHot)", Entry.checkCyclicListIntegrity(handHot))
      .check("checkCyclicListIntegrity(handCold)", Entry.checkCyclicListIntegrity(handCold))
      .checkEquals("getCyclicListEntryCount(handHot) == hotSize", Entry.getCyclicListEntryCount(handHot), hotSize)
      .checkEquals("getCyclicListEntryCount(handCold) == coldSize", Entry.getCyclicListEntryCount(handCold), coldSize)
      .checkEquals("Ghost.listSize(ghostHead) == ghostSize", Ghost.listSize(ghostHead), ghostSize);
  }

  @Override
  public String getExtraStatistics() {
    return "coldSize=" + coldSize +
      ", hotSize=" + hotSize +
      ", hotMaxSize=" + hotMax +
      ", ghostSize=" + ghostSize +
      ", coldHits=" + (coldHits + sumUpListHits(handCold)) +
      ", hotHits=" + (hotHits + sumUpListHits(handHot)) +
      ", ghostHits=" + ghostHits +
      ", coldRunCnt=" + coldRunCnt +// identical to the evictions anyways
      ", coldScanCnt=" + coldScanCnt +
      ", cold24hCnt=" + cold24hCnt +
      ", hotRunCnt=" + hotRunCnt +
      ", hotScanCnt=" + hotScanCnt +
      ", hot24hCnt=" + hot24hCnt;
  }

  public static class Tunable extends TunableConstants {

    int hotMaxPercentage = 97;

    int hitCounterDecreaseShift = 6;

  }

  private Ghost lookupGhost(int _hash) {
    Ghost[] tab = ghosts;
    int n = tab.length;
    int _mask = n - 1;
    int idx = _hash & (_mask);
    Ghost e = tab[idx];
    while (e != null) {
      if (e.hash == _hash) {
        return e;
      }
      e = e.another;
    }
    return null;
  }

  private void insertGhost(Ghost e2, int _hash) {
    Ghost[] tab = ghosts;
    int n = tab.length;
    int _mask = n - 1;
    int idx = _hash & (_mask);
    Ghost e = tab[idx];
    e2.another = e;
    tab[idx] = e2;
    ghostSize++;
    int _maxFill = n * GHOST_LOAD_PERCENT / 100;
    if (ghostSize > _maxFill) {
      expand();
    }
  }

  private void expand() {
    Ghost[] tab = ghosts;
    int n = tab.length;
    int _mask;
    int idx;Ghost[] _newTab = new Ghost[n * 2];
    _mask = _newTab.length - 1;
    for (Ghost g : tab) {
      while (g != null) {
        idx = g.hash & _mask;
        Ghost _next = g.another;
        g.another = _newTab[idx];
        _newTab[idx] = g;
        g = _next;
      }
    }
    ghosts = _newTab;
  }

  private Ghost removeGhost(int _hash) {
    Ghost[] tab = ghosts;
    int n = tab.length;
    int _mask = n - 1;
    int idx = _hash & (_mask);
    Ghost e = tab[idx];
    if (e.hash == _hash) {
      tab[idx] = e.another;
      ghostSize--;
      return e;
    } else {
      while (e != null) {
        Ghost _another = e.another;
        if (_another.hash == _hash) {
          e.another = _another.another;
          ghostSize--;
          return _another;
        }
        e = _another;
      }
    }
    return null;
  }

  private boolean removeGhost(Ghost g, int _hash) {
    Ghost[] tab = ghosts;
    int n = tab.length;
    int _mask = n - 1;
    int idx = _hash & (_mask);
    Ghost e = tab[idx];
    if (e == g) {
      tab[idx] = e.another;
      ghostSize--;
      return true;
    } else {
      while (e != null) {
        Ghost _another = e.another;
        if (_another == g) {
          e.another = _another.another;
          ghostSize--;
          return true;
        }
        e = _another;
      }
    }
    return false;
  }

  private int countGhostsInHash() {
    int _entryCount = 0;
    for (Ghost e : ghosts) {
      while (e != null) {
        _entryCount++;
        e = e.another;
      }
    }
    return _entryCount;
  }

  private static class Ghost {

    int hash;
    Ghost another;
    Ghost next;
    Ghost prev;

    Ghost shortCurcuit() {
      return next = prev = this;
    }

    static void removeFromList(final Ghost e) {
      e.prev.next = e.next;
      e.next.prev = e.prev;
      e.next = e.prev = null;
    }

    static void insertInList(final Ghost _head, final Ghost e) {
      e.prev = _head;
      e.next = _head.next;
      e.next.prev = e;
      _head.next = e;
    }

    static void moveToFront(final Ghost _head, final Ghost e) {
      removeFromList(e);
      insertInList(_head, e);
    }

    static int listSize(final Ghost _head) {
      int _count = 0;
      Ghost e = _head;
      while ((e = e.next) != _head) { _count++; }
      return _count;
    }

  }

}
