
package de.dfki.sds.aticsqlite.s16n;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import org.apache.commons.collections4.BidiMap;
import org.apache.commons.collections4.bidimap.DualHashBidiMap;
import org.apache.commons.lang3.Validate;

/**
 * Allocates for objects indices.
 * @param <T> type of objects
 */
public class IndexAllocation<T> extends AbstractCollection<T> {

    private BidiMap<T, Integer> map;
    
    //lazy calculated
    private Integer maxIndex;
    
    //to set some elements hidden
    private Set<T> hidden;
    
    public IndexAllocation() {
        map = new DualHashBidiMap<>();
        hidden = new LinkedHashSet<>();
    }

    /**
     * Adds an element at the end.
     * Shortcut for <code>put(e, size())</code>.
     * @param e non-null
     * @return true
     */
    @Override
    public boolean add(T e) {
        put(e, size());
        return true;
    }
    
    /**
     * Sets for an object an index.
     * @param object if the non-null object has already an index, it is overwritten.
     * @param index a non-negative 0-indexed number
     * @throws IllegalArgumentException if the index is already allocated
     */
    public void put(T object, Integer index) {
        Validate.notNull(object);
        if(index < 0)
            throw new IllegalArgumentException("index < 0");
        
        if(map.inverseBidiMap().containsKey(index)) {
            throw new IllegalStateException("index is already allocated: " + index);
        }
        
        map.put(object, index);
        
        maxIndex = null;
    }
    
    /**
     * Sets for an object an index.
     * Does not throw IllegalArgumentException if the index is already allocated.
     * @param object if the non-null object has already an index, it is overwritten.
     * @param index a non-negative 0-indexed number
     */
    public void putOverwrite(T object, Integer index) {
        Validate.notNull(object);
        if(index < 0)
            throw new IllegalArgumentException("index < 0");
        
        map.put(object, index);
        
        maxIndex = null;
    }
    
    /**
     * Shifts from an index the right indices by a given (delta) shift.
     * @param fromIndex 0 means the first entry is free after shift.
     * @param shift non-negative, how far to the right the shift goes
     */
    public void shiftRight(int fromIndex, int shift) {
        if(fromIndex < 0) throw new IllegalArgumentException("index < 0");
        if(shift < 0) throw new IllegalArgumentException("shift < 0");
        
        if(shift == 0 || map.isEmpty())
            return;
        
        List<Entry<T, Integer>> entries = new ArrayList<>(map.entrySet());
        entries.removeIf(e -> e.getValue() < fromIndex);
        entries.sort((a,b) -> Integer.compare(b.getValue(), a.getValue()));
        
        for(Entry<T, Integer> entry : entries) {
            put(entry.getKey(), entry.getValue() + shift);
        }
        
        maxIndex = null;
    }
    
    /**
     * Removes the object from the allocation.
     * @param object non-null
     * @return the index (may be null)
     */
    public Integer removeObject(T object) {
        Validate.notNull(object);
        maxIndex = null;
        return map.remove(object);
    }
    
    /**
     * Removes the object from the allocation.
     * @param index non-negative index
     * @return the object (may be null)
     */
    public T removeIndex(int index) {
        if(index < 0) throw new IllegalArgumentException("index < 0");
        maxIndex = null;
        return map.inverseBidiMap().remove(index);
    }

    /**
     * Removes all elements together with their indices.
     */
    @Override
    public void clear() {
        map.clear();
        maxIndex = null;
    }
    
    /**
     * Returns the object for a given index.
     * If object is hidden, returns null.
     * @param index non-negative index
     * @return can be null
     */
    public T getObject(int index) {
        if(index < 0) throw new IllegalArgumentException("index < 0");
        T element = map.inverseBidiMap().get(index);
        if(isHidden(element))
            return null;
        return element;
    }
    
    /**
     * Returns the index for a given object.
     * @param object
     * @return can be null
     */
    public Integer getIndex(T object) {
        Validate.notNull(object);
        return map.get(object);
    }

    /**
     * Calculates the maximum index.
     * @return null if allocation is empty
     */
    public Integer getMaxIndex() {
        if(map.isEmpty())
            return null;
        
        //calculate it
        if(maxIndex == null) {
            maxIndex = map.values().stream().mapToInt(i -> i).max().getAsInt();
        }
        
        return maxIndex;
    }
    
    /**
     * A list representation where objects with unused indexes are null,
     * e.g. <code>["A", null, "C"]</code>.
     * @return an empty list if no index is allocated.
     */
    public List<T> toList() {
        if(map.isEmpty())
            return new ArrayList<>();
        
        int len = getMaxIndex() + 1;
        
        List<T> list = new ArrayList<>(len);
        for(int i = 0; i < len; i++) {
            list.add(getObject(i));
        }
        
        return list;
    }

    /**
     * A list representation where all non-null objects are listed.
     * @return an empty list if no index is allocated.
     */
    public List<T> toDeflatedList() {
        if(map.isEmpty())
            return new ArrayList<>();
        
        int len = getMaxIndex() + 1;
        
        List<T> list = new ArrayList<>(len);
        for(int i = 0; i < len; i++) {
            T t = getObject(i);
            if(t != null) {
                list.add(t);
            }
        }
        
        return list;
    }
    
    /**
     * Iterates over all objects (also null).
     * Calls {@link #toList() }.
     * @return 
     */
    @Override
    public Iterator<T> iterator() {
        return toList().iterator();
    }

    /**
     * Returns the number of objects (also null).
     * Calls {@link #toList() }.
     * @return 
     */
    @Override
    public int size() {
        return toList().size();
    }

    /**
     * Returns true if the element has an index allocated.
     * @param element non-null
     * @return true if contained
     */
    @Override
    public boolean contains(Object element) {
        Validate.notNull(element);
        return map.containsKey(element);
    }
    
    //--------------
    //hidden
    
    /**
     * Returns true if element is hidden.
     * @param element if null returns false
     * @return true if hidden
     */
    public boolean isHidden(T element) {
        if(element == null)
            return false;
        return hidden.contains(element);
    }

    /**
     * Use this collection to temporarily hide elements.
     * This has an effect on {@link #getObject(int) } and all related methods.
     * Hidden elements will be returned as null.
     * @return a set to add hidden elements.
     */
    /*package*/ Set<T> getHidden() {
        return hidden;
    }
    
}
