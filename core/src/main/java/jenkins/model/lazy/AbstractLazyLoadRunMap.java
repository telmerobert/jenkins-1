/*
 * The MIT License
 *
 * Copyright (c) 2012, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package jenkins.model.lazy;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import static jenkins.model.lazy.AbstractLazyLoadRunMap.Direction.*;
import static jenkins.model.lazy.Boundary.*;

/**
 * {@link SortedMap} that keeps build records by their build numbers, in the descending order
 * (newer ones first.)
 *
 * <p>
 * The main thing about this class is that it encapsulates the lazy loading logic.
 * That is, while this class looks and feels like a normal {@link SortedMap} from outside,
 * it actually doesn't have every item in the map instantiated yet. As items in the map get
 * requested, this class {@link #retrieve(File) retrieves them} on demand, one by one.
 *
 * <p>
 * The lookup is primarily done by using the build number as the key (hence the key type is {@link Integer}),
 * but this class also provides look up based on {@linkplain #getIdOf(Object) the build ID}.
 *
 * <p>
 * This class makes the following assumption about the on-disk layout of the data:
 *
 * <ul>
 *     <li>Every build is stored in a directory, named after its ID.
 *     <li>ID and build number are in the consistent order. That is,
 *         if there are two builds #M and #N, {@code M>N <=> M.id > N.id}.
 * </ul>
 *
 * <p>
 * On certain platforms, there are symbolic links named after build numbers that link to the build ID.
 * If these are available, they are used as a hint to speed up the lookup. Otherwise
 * we rely on the assumption above and perform a binary search to locate the build.
 * (notice that we'll have to do linear search if we don't have the consistent ordering assumption,
 * which robs the whole point of doing lazy loading.)
 *
 * <p>
 * Some of the {@link SortedMap} operations are weakly implemented. For example,
 * {@link #size()} may be inaccurate because we only count the number of directories that look like
 * build records, without checking if they are loadable. But these weaknesses aren't distinguishable
 * from concurrent modifications, where another thread deletes a build while one thread iterates them.
 *
 * <p>
 * Some of the {@link SortedMap} operations are inefficiently implemented, by
 * {@linkplain #all() loading all the build records eagerly}. We hope to replace
 * these implementations by more efficient lazy-loading ones as we go.
 *
 * <p>
 * Object lock of {@code this} is used to make sure mutation occurs sequentially.
 * That is, ensure that only one thread is actually calling {@link #retrieve(File)} and
 * updating {@link #byNumber} and {@link #byId}.
 *
 * @author Kohsuke Kawaguchi
 * @since 1.LAZYLOAD
 */
public abstract class AbstractLazyLoadRunMap<R> extends AbstractMap<Integer,R> implements SortedMap<Integer,R> {
    /**
     * Used in {@link #all()} to quickly determine if we've already loaded everything.
     */
    private boolean fullyLoaded;

    /**
     * Stores the mapping from build number to build, for builds that are already loaded.
     */
    // copy on write
    private volatile TreeMap<Integer,R> byNumber = new TreeMap<Integer,R>(COMPARATOR);

    /**
     * Stores the build ID to build number for builds that we already know
     */
    // copy on write
    private volatile TreeMap<String,R> byId = new TreeMap<String,R>();

    /**
     * Build IDs found as directories, in the ascending order.
     */
    // copy on write
    private SortedList<String> idOnDisk = new SortedList<String>(Collections.<String>emptyList());

    /**
     * Build bumber shortcuts found on disk, in the ascending order.
     */
    // copy on write
    private SortedIntList numberOnDisk = new SortedIntList(0);

    /**
     * Base directory for data.
     * In effect this is treated as a final field, but can't mark it final
     * because the compatibility requires that we make it settable
     * in the first call after the constructor.
     */
    private File dir;

    protected AbstractLazyLoadRunMap(File dir) {
        initBaseDir(dir);
    }

    @Restricted(NoExternalUse.class)
    protected void initBaseDir(File dir) {
        assert this.dir==null;
        this.dir = dir;
        if (dir!=null)
            loadIdOnDisk();
    }

    private void loadIdOnDisk() {
        String[] buildDirs = dir.list(createDirectoryFilter());
        if (buildDirs==null)    buildDirs=EMPTY_STRING_ARRAY;
        // wrap into ArrayList to enable mutation
        Arrays.sort(buildDirs);
        idOnDisk = new SortedList(new ArrayList<String>(Arrays.asList(buildDirs)));

        // TODO: should we check that shortcuts is a symlink?
        String[] shortcuts = dir.list();
        if (shortcuts==null)    shortcuts=EMPTY_STRING_ARRAY;
        SortedIntList list = new SortedIntList(shortcuts.length/2);
        for (String s : shortcuts) {
            try {
                list.add(Integer.parseInt(s));
            } catch (NumberFormatException e) {
                // this isn't a shortcut
            }
        }
        list.sort();
        numberOnDisk = list;
    }

    public Comparator<? super Integer> comparator() {
        return COMPARATOR;
    }

    /**
     * If we have non-zero R in memory, we can return false right away.
     * If we have zero R in memory, try loading one and see if we can find something.
     */
    @Override
    public boolean isEmpty() {
        return byId.isEmpty() && search(Integer.MAX_VALUE, DESC)==null;
    }

    @Override
    public Set<Entry<Integer, R>> entrySet() {
        return Collections.unmodifiableSet(all().entrySet());
    }

    /**
     * Returns a read-only view of records that has already been loaded.
     */
    public SortedMap<Integer,R> getLoadedBuilds() {
        return Collections.unmodifiableSortedMap(byNumber);
    }

    /**
     * @param fromKey
     *      Biggest build number to be in the returned set.
     * @param toKey
     *      Smallest build number-1 to be in the returned set (-1 because this is exclusive)
     */
    public SortedMap<Integer, R> subMap(Integer fromKey, Integer toKey) {
        // TODO: if this method can produce a lazy map, that'd be wonderful
        // because due to the lack of floor/ceil/higher/lower kind of methods
        // to look up keys in SortedMap, various places of Jenkins rely on
        // subMap+firstKey/lastKey combo.

        R start = search(fromKey, DESC);
        if (start==null)    return EMPTY_SORTED_MAP;

        R end = search(toKey, ASC);
        if (end==null)      return EMPTY_SORTED_MAP;

        for (R i=start; i!=end; ) {
            i = search(getNumberOf(i)-1,DESC);
            assert i!=null;
        }

        return Collections.unmodifiableSortedMap(byNumber.subMap(fromKey, toKey));
    }

    public SortedMap<Integer, R> headMap(Integer toKey) {
        return subMap(Integer.MAX_VALUE, toKey);
    }

    public SortedMap<Integer, R> tailMap(Integer fromKey) {
        return subMap(fromKey, Integer.MIN_VALUE);
    }

    public Integer firstKey() {
        R r = newestBuild();
        if (r==null)    throw new NoSuchElementException();
        return getNumberOf(r);
    }

    public Integer lastKey() {
        R r = oldestBuild();
        if (r==null)    throw new NoSuchElementException();
        return getNumberOf(r);
    }

    public R newestBuild() {
        return search(Integer.MAX_VALUE, DESC);
    }

    public R oldestBuild() {
        return search(Integer.MIN_VALUE, ASC);
    }

    @Override
    public R get(Object key) {
        if (key instanceof Integer) {
            int n = (Integer) key;
            return get(n);
        }
        return super.get(key);
    }

    public R get(int n) {
        return search(n,Direction.EXACT);
    }

    /**
     * Finds the build #M where M is nearby the given 'n'.
     *
     * <p>
     *
     *
     * @param n
     *      the index to start the search from
     * @param d
     *      defines what we mean by "nearby" above.
     *      If EXACT, find #N or return null.
     *      If ASC, finds the closest #M that satisfies M>=N.
     *      If DESC, finds the closest #M that satisfies M<=N.
     */
    public R search(final int n, final Direction d) {
        Entry<Integer, R> c = byNumber.ceilingEntry(n);
        if (c!=null && c.getKey()== n)  return c.getValue();    // found the exact #n

        // at this point we know that we don't have #n loaded yet

        {// check numberOnDisk as a cache to see if we can find it there
            int npos = numberOnDisk.find(n);
            if (npos>=0) {// found exact match
                R r = load(numberOnDisk.get(npos), true);
                if (r!=null)
                    return r;
            }

            switch (d) {
            case ASC:
            case DESC:
                // didn't find the exact match, but what's the nearest ascending value in the cache?
                int neighbor = (d==ASC?HIGHER:LOWER).apply(npos);
                if (numberOnDisk.isInRange(neighbor)) {
                    R r = getByNumber(numberOnDisk.get(neighbor));
                    if (r!=null) {
                        // make sure that the cache is accurate by looking at the previous ID
                        // and it actually satisfies the constraint
                        int prev = (d==ASC?LOWER:HIGHER).apply(idOnDisk.find(getIdOf(r)));
                        if (idOnDisk.isInRange(prev)) {
                            R pr = getById(idOnDisk.get(prev));
                            // sign*sign is making sure that #pr and #r sandwiches #n.
                            if (pr!=null && signOfCompare(getNumberOf(pr),n)*signOfCompare(n,getNumberOf(r))>0)
                                return r;
                            else {
                                // cache is lying. there's something fishy.
                                // ignore the cache and do the slow search
                            }
                        } else {
                            // r is the build with youngest ID
                            return r;
                        }
                    } else {
                        // cache says we should have a build but we didn't.
                        // ignore the cache and do the slow search
                    }
                }
                break;
            case EXACT:
                // fall through
            }

            // didn't find it in the cache, but don't give up yet
            // maybe the cache just doesn't exist.
            // so fall back to the slow search
        }

        // capture the snapshot and work off with it since it can be changed by other threads
        SortedList<String> idOnDisk = this.idOnDisk;

        // slow path: we have to find the build from idOnDisk.
        // first, narrow down the candidate IDs to try by using two known number-to-ID mapping
        if (idOnDisk.isEmpty())     return null;

        Entry<Integer, R> f = byNumber.floorEntry(n);

        // if bound is null, use a sentinel value
        String cid = c==null ? "\u0000"  : getIdOf(c.getValue());
        String fid = f==null ? "\uFFFF" : getIdOf(f.getValue());

        // We know that the build we are looking for exists in this range
        // we will narrow this down via binary search
        int lo = idOnDisk.higher(cid);
        int hi = idOnDisk.lower(fid)+1;

        int pivot;
        while (true) {
            pivot = (lo+hi)/2;
            if (hi<=lo)     break;  // end of search

            R r = load(idOnDisk.get(pivot), true);
            if (r==null) {
                // this ID isn't valid. get rid of that and retry pivot
                hi--;
                idOnDisk.remove(pivot);
                continue;
            }

            int found = getNumberOf(r);
            if (found==n)
                return r;   // exact match

            if (found<n)    lo = pivot+1;   // the pivot was too small. look in the upper half
            else            hi = pivot;     // the pivot was too big. look in the lower half
        }

        // didn't find the exact match
        // 'pivot' points to the insertion point on idOnDisk
        switch (d) {
        case ASC:
            if (hi==idOnDisk.size())    return null;
            return getById(idOnDisk.get(hi));
        case DESC:
            if (lo<=0)                 return null;
            return getById(idOnDisk.get(lo-1));
        case EXACT:
            return null;
        default:
            throw new AssertionError();
        }
    }

    /**
     * sign of (a-b).
     */
    private static int signOfCompare(int a, int b) {
        if (a>b)    return 1;
        if (a<b)    return -1;
        return 0;
    }

    public R getById(String id) {
        if (byId.containsKey(id))
            return byId.get(id);
        return load(id,true);
    }

    public R getByNumber(int n) {
        return search(n,Direction.EXACT);
    }

    public final R put(R value) {
        return put(getNumberOf(value),value);
    }

    @Override
    public synchronized R put(Integer key, R r) {
        String id = getIdOf(r);
        int n = getNumberOf(r);

        copy();
        R old = byId.put(id,r);
        byNumber.put(n,r);

        /*
            search relies on the fact that every objet added via
            put() method be available in the xyzOnDisk index, so I'm adding them here
            however, this is awfully inefficient. I wonder if there's any better way to do this?
         */
        if (!idOnDisk.contains(id)) {
            ArrayList<String> a = new ArrayList<String>(idOnDisk);
            a.add(id);
            Collections.sort(a);
            idOnDisk = new SortedList<String>(a);
        }

        if (!numberOnDisk.contains(n)) {
            SortedIntList a = new SortedIntList(numberOnDisk);
            a.add(n);
            a.sort();
            numberOnDisk = a;
        }

        return old;
    }

    @Override
    public synchronized void putAll(Map<? extends Integer,? extends R> rhs) {
        copy();
        for (R r : rhs.values()) {
            byId.put(getIdOf(r),r);
            byNumber.put(getNumberOf(r),r);
        }
    }

    /**
     * Loads all the build records to fully populate the map.
     * Calling this method results in eager loading everything,
     * so the whole point of this class is to avoid this call as much as possible
     * for typical code path.
     *
     * @return
     *      fully populated map.
     */
    private TreeMap<Integer,R> all() {
        if (!fullyLoaded) {
            synchronized (this) {
                if (!fullyLoaded) {
                    copy();
                    for (String id : idOnDisk) {
                        if (!byId.containsKey(id))
                            load(id,false); // copy() called above, so no need to copy inside
                    }
                    fullyLoaded = true;
                }
            }
        }
        return byNumber;
    }

    /**
     * Creates a duplicate for the COW data structure in preparation for mutation.
     */
    private void copy() {
        byId     = new TreeMap<String, R>(byId);
        byNumber = new TreeMap<Integer,R>(byNumber);
    }

    /**
     * Tries to load the record #N by using the shortcut.
     * 
     * @return null if the data failed to load.
     */
    protected R load(int n, boolean copy) {
        R r = null;
        File shortcut = new File(dir,String.valueOf(n));
        if (shortcut.isDirectory()) {
            synchronized (this) {
                r = load(shortcut,copy);

                // make sure what we actually loaded is #n,
                // because the shortcuts can lie.
                if (r!=null && getNumberOf(r)!=n)
                    r = null;

                if (r==null) {
                    // if failed to locate, record that fact
                    SortedIntList update = new SortedIntList(numberOnDisk);
                    update.removeValue(n);
                    numberOnDisk = update;
                }
            }
        }
        return r;
    }


    protected R load(String id, boolean copy) {
        return load(new File(dir,id),copy);
    }

    protected synchronized R load(File dataDir, boolean copy) {
        try {
            R r = retrieve(dataDir);
            if (r==null)    return null;

            if (copy)   copy();
            byId.put(getIdOf(r),r);
            byNumber.put(getNumberOf(r),r);
            return r;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load "+dataDir,e);
        }
        return null;
    }

    protected abstract int getNumberOf(R r);
    protected abstract String getIdOf(R r);

    /**
     * Parses {@code R} instance from data in the specified directory.
     *
     * @return
     *      null if the parsing failed.
     * @throws IOException
     *      if the parsing failed. This is just like returning null
     *      except the caller will catch the exception and report it.
     */
    protected abstract R retrieve(File dir) throws IOException;

    public synchronized boolean removeValue(R run) {
        copy();
        byNumber.remove(getNumberOf(run));
        R old = byId.remove(getIdOf(run));
        return old!=null;
    }

    /**
     * Replaces all the current loaded Rs with the given ones.
     */
    public synchronized void reset(TreeMap<Integer,R> builds) {
        TreeMap<Integer, R> byNumber = new TreeMap<Integer,R>(COMPARATOR);
        TreeMap<String, R> byId = new TreeMap<String, R>(COMPARATOR);
        for (R r : builds.values()) {
            byId.put(getIdOf(r),r);
            byNumber.put(getNumberOf(r),r);
        }

        this.byNumber = byNumber;
        this.byId = byId;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public boolean equals(Object o) {
        return o==this;
    }

    /**
     * Lists the actual data directory
     */
    protected abstract FilenameFilter createDirectoryFilter();

    private static final Comparator<Comparable> COMPARATOR = new Comparator<Comparable>() {
        public int compare(Comparable o1, Comparable o2) {
            return -o1.compareTo(o2);
        }
    };
    
    public enum Direction {
        ASC, DESC, EXACT
    }

    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    private static final SortedMap EMPTY_SORTED_MAP = Collections.unmodifiableSortedMap(new TreeMap());

    private static final Logger LOGGER = Logger.getLogger(AbstractLazyLoadRunMap.class.getName());
}
