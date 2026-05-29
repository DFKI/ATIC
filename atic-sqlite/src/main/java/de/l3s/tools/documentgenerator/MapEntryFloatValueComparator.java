package de.l3s.tools.documentgenerator;

import java.util.Comparator;
import java.util.Map;

public class MapEntryFloatValueComparator<T> implements Comparator<Map.Entry<T, Float>>{
	public int compare(Map.Entry<T, Float> a, Map.Entry<T, Float> b) {
		return a.getValue().compareTo(b.getValue());
	}
}
